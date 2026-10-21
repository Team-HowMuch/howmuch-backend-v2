package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.WidgetTokenPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetRefreshStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.support.SettlementDepositTestCleanup;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
class SettlementWidgetHttpIntegrationTest {
    @MockitoBean private com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort bankLookupBudget;

    private static final String PATH = "/api/v2/settlements/widget-summary";
    private static final String ACCOUNT = "1002123456789";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenPort tokens;
    @Autowired private WidgetTokenPort widgetTokens;
    @Autowired private AuthSessionPort sessions;
    @Autowired private LoadUserPort users;
    @Autowired private SettlementWidgetRefreshStorePort refreshStore;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private ObjectMapper json;
    @MockitoBean private BankTransactionPort bank;
    @MockitoBean private ExternalConnectionStorePort connections;
    @MockitoBean private BankCatalog catalog;
    @MockitoBean private com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService notifications;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private SettlementUnpaidFixtures fixtures;
    private Long creator;
    private Long payer;
    private String sessionId;
    private String access;
    private String widget;
    private Long accountId;
    private Long connectionId;
    private final List<UUID> owned = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SettlementUnpaidFixtures(jdbc);
        creator = fixtures.user();
        payer = fixtures.user();
        sessionId = UUID.randomUUID().toString();
        sessions.save(new AuthSession(creator, sessionId, "synthetic-refresh", now().plusDays(3)));
        access = tokens.createAccessToken(users.findById(creator).orElseThrow(), sessionId);
        var response = request("POST", "/api/v2/auth/widget-token", access);
        assertThat(response.statusCode()).isEqualTo(200);
        widget = json.readTree(response.body()).path("data").path("widgetToken").stringValue();
        assertThat(tokens.parsePayload(widget).orElseThrow().expiresAt()).isAfter(now().plusDays(2));
    }

    @AfterEach
    void tearDown() {
        sessions.deleteByUserId(creator);
        sessions.deleteByUserId(payer);
        boolean preserveAccount = accountId != null && SettlementDepositTestCleanup.preserveAccount(jdbc, accountId);
        for (UUID id : owned) {
            if (!SettlementDepositTestCleanup.preserveSettlement(jdbc, id)) {
                SettlementDepositTestCleanup.deleteDepositsWhenMutable(jdbc, id);
                jdbc.update("DELETE FROM settlements WHERE id=?", id);
            }
        }
        if (accountId != null && !preserveAccount) {
            SettlementDepositTestCleanup.deleteDepositsForAccountWhenMutable(jdbc, accountId);
            jdbc.update("DELETE FROM account_transactions WHERE account_id=?", accountId);
            jdbc.update("DELETE FROM accounts WHERE id=?", accountId);
        }
        if (connectionId != null && !preserveAccount) jdbc.update("DELETE FROM external_connections WHERE id=?", connectionId);
        clearRefresh();
        fixtures.close();
        owned.clear();
    }

    @Test
    void widgetScopeAllowsOnlyExplicitEndpointsAndRejectsRefreshTokens() throws Exception {
        assertThat(request("GET", PATH, widget).statusCode()).isEqualTo(200);
        assertThat(request("POST", PATH + "/refresh", widget).statusCode()).isEqualTo(200);
        for (String path : List.of("/api/v2/settlements", "/api/v2/settlements/unpaid-summary", "/api/v2/accounts")) {
            assertThat(request("GET", path, widget).statusCode()).isEqualTo(401);
        }
        assertThat(request("POST", "/api/v2/auth/widget-token", widget).statusCode()).isEqualTo(401);
        assertThat(request("POST", PATH, widget).statusCode()).isEqualTo(401);
        assertThat(request("GET", PATH + "/refresh", widget).statusCode()).isEqualTo(401);
        String refresh = tokens.createRefreshToken(users.findById(creator).orElseThrow(), sessionId);
        assertThat(request("GET", PATH, refresh).statusCode()).isEqualTo(401);
        assertThat(request("GET", PATH, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", PATH, "bad-jwt").statusCode()).isEqualTo(401);
        verifyNoInteractions(bank, connections);
    }

    @Test
    void individualReconcileUsesWidgetAfterAccessExpiryAndKeepsSessionBoundary() throws Exception {
        UUID id = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "위젯 참여자");
        bankReturns(List.of(income(400, "위젯 참여자 입금")));
        var properties = new com.amateur.howmuchbackend.infra.security.config.JwtProperties(
                System.getenv("JWT_SECRET"), -1, 1209600);
        var expiredProvider = new com.amateur.howmuchbackend.adapter.out.security.jwt.JwtTokenProvider(properties);
        String expiredAccess = expiredProvider.createAccessToken(users.findById(creator).orElseThrow(), sessionId);
        String path = "/api/v2/settlements/" + id + "/deposits/reconcile";
        assertThat(request("POST", path, expiredAccess).statusCode()).isEqualTo(401);
        verifyNoInteractions(bank);
        assertThat(request("POST", path, widget).statusCode()).isEqualTo(200);
        verify(bank, times(1)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT sum(t.amount) FROM settlement_deposits d "
                + "JOIN account_transactions t ON t.id=d.account_transaction_id WHERE d.settlement_id=?",
                Long.class, id)).isEqualTo(400);
        sessions.deleteByUserId(creator);
        assertThat(request("POST", path, widget).statusCode()).isEqualTo(401);
        verifyNoMoreInteractions(bank);
    }

    @Test
    void individualWidgetReconcileRejectsOtherOwnersAndNonScopedMethods() throws Exception {
        UUID other = fixtures.settlement(payer, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        String base = "/api/v2/settlements/" + other + "/deposits";
        assertThat(request("POST", base + "/reconcile", widget).statusCode()).isEqualTo(403);
        for (String method : List.of("GET", "PUT", "DELETE")) {
            assertThat(request(method, base + "/reconcile", widget).statusCode()).isEqualTo(401);
        }
        assertThat(request("GET", base, widget).statusCode()).isEqualTo(401);
        assertThat(request("POST", base + "/reconcile/extra", widget).statusCode()).isEqualTo(401);
        assertThat(request("POST", "/api/v2/settlements/not-a-uuid/deposits/reconcile", widget).statusCode()).isEqualTo(401);
        verifyNoInteractions(bank, connections);
    }

    @Test
    void widgetIsRevokedOnLogoutNewLoginAndExpiry() throws Exception {
        sessions.deleteByUserId(creator);
        assertThat(request("GET", PATH, widget).statusCode()).isEqualTo(401);
        sessions.save(new AuthSession(creator, "new-session", "unused", now().plusDays(3)));
        assertThat(request("GET", PATH, widget).statusCode()).isEqualTo(401);
        String expired = widgetTokens.createWidgetToken(creator, "new-session", now().minusSeconds(1));
        assertThat(request("GET", PATH, expired).statusCode()).isEqualTo(401);
        sessions.save(new AuthSession(creator, sessionId, "unused", now().minusSeconds(1)));
        assertThat(request("GET", PATH, widget).statusCode()).isEqualTo(401);
    }

    @Test
    void activeSessionRefreshKeepsWidgetValidWithoutGivingItRefreshAuthority() throws Exception {
        var old = sessions.findByUserId(creator).orElseThrow();
        assertThat(sessions.rotate(old, new AuthSession(creator, sessionId, "rotated", now().plusDays(5)))).isTrue();
        assertThat(request("GET", PATH, widget).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/api/v2/auth/widget-token", widget).statusCode()).isEqualTo(401);
    }

    @Test
    void summarySeparatesActorsAndAllProgressStatesWithoutTruncating() throws Exception {
        UUID receive = fixtures.settlement(creator, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        UUID share = fixtures.share(receive, fixtures.participant(receive, payer), 10_000);
        fixtures.share(receive, fixtures.participant(receive, creator), 99_999);
        fixtures.deposit(creator, receive, share, 3_000);
        UUID guest = fixtures.participant(receive, null);
        fixtures.share(receive, guest, 1_000);
        UUID requested = fixtures.settlement(creator, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        fixtures.participant(requested, payer);
        for (SettlementStatus status : List.of(SettlementStatus.DRAFT, SettlementStatus.SUSPENDED, SettlementStatus.COMPLETED)) {
            fixtures.settlement(creator, status, SettlementAllocationType.DUTCH_PAY);
        }
        UUID deleted = fixtures.settlement(creator, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        fixtures.markDeleted(deleted);
        UUID pay = fixtures.settlement(payer, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        UUID payableShare = fixtures.share(pay, fixtures.participant(pay, creator), 8_000);
        fixtures.deposit(payer, pay, payableShare, 2_000);
        UUID select = fixtures.settlement(payer, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        fixtures.participant(select, creator);
        UUID wait = fixtures.settlement(payer, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        fixtures.submitZero(wait, fixtures.participant(wait, creator));
        for (int i = 0; i < 12; i++) {
            UUID extra = fixtures.settlement(payer, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
            fixtures.participant(extra, creator);
        }
        var data = data(request("GET", PATH + "?userId=" + payer, widget));
        assertThat(data.path("receivable").path("totalCount").intValue()).isEqualTo(2);
        assertThat(data.path("receivable").path("totalAmount").longValue()).isEqualTo(108_000);
        assertThat(find(data, "receivable", receive).path("unpaidParticipantCount").intValue()).isEqualTo(2);
        assertThat(find(data, "receivable", requested).path("amountUnconfirmed").booleanValue()).isTrue();
        assertThat(data.path("payable").path("totalCount").intValue()).isEqualTo(15);
        assertThat(data.path("payable").path("settlements").size()).isEqualTo(15);
        assertThat(data.path("payable").path("totalAmount").longValue()).isEqualTo(6_000);
        assertThat(find(data, "payable", pay).path("status").stringValue()).isEqualTo("PAYABLE");
        assertThat(find(data, "payable", select).path("status").stringValue()).isEqualTo("SELECTION_REQUIRED");
        assertThat(find(data, "payable", select).path("remainingAmount").isNull()).isTrue();
        assertThat(find(data, "payable", wait).path("status").stringValue()).isEqualTo("WAITING");
        assertThat(find(data, "payable", wait).path("remainingAmount").longValue()).isZero();
        assertThat(data.path("lastReconciledAt").isNull()).isTrue();
        verifyNoInteractions(bank, connections);
    }

    @Test
    void zeroAmountDutchPayIsExcludedButUnconfirmedItemAllocationRemains() throws Exception {
        UUID receive = fixtures.settlement(creator, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        fixtures.participant(receive, creator);
        fixtures.share(receive, fixtures.participant(receive, payer), 10_000);
        fixtures.participant(receive, null); // 0원 비회원 참여 기록
        UUID creatorOnly = fixtures.settlement(creator, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        fixtures.share(creatorOnly, fixtures.participant(creatorOnly, creator), 10_000);
        fixtures.participant(creatorOnly, payer);
        UUID zeroPay = fixtures.settlement(payer, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        fixtures.share(zeroPay, fixtures.participant(zeroPay, payer), 10_000);
        fixtures.participant(zeroPay, creator); // 로그인 사용자의 0원 배분
        UUID select = fixtures.settlement(payer, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        fixtures.participant(select, creator);

        var data = data(request("GET", PATH, widget));

        assertThat(data.path("receivable").path("totalCount").intValue()).isEqualTo(2);
        assertThat(data.path("receivable").path("totalAmount").longValue()).isEqualTo(10_000);
        assertThat(find(data, "receivable", receive).path("unpaidParticipantCount").intValue()).isOne();
        assertThat(find(data, "receivable", creatorOnly).path("unpaidParticipantCount").intValue()).isZero();
        assertThat(find(data, "receivable", creatorOnly).path("remainingAmount").longValue()).isZero();
        assertThat(data.path("payable").path("totalCount").intValue()).isOne();
        assertThat(data.path("payable").path("settlements").size()).isOne();
        assertThat(find(data, "payable", select).path("status").stringValue()).isEqualTo("SELECTION_REQUIRED");
        assertThat(find(data, "payable", select).path("remainingAmount").isNull()).isTrue();
        verifyNoInteractions(bank, connections);
    }

    @Test
    void requestedPreviewIncludesCreatorSelectionReceiptDiscountAndDeliveryFee() throws Exception {
        UUID id = fixtures.settlement(creator, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        UUID self = fixtures.participant(id, creator);
        fixtures.share(id, self, 4_200);
        fixtures.participant(id, payer);
        UUID expense = UUID.randomUUID();
        UUID selectedItem = UUID.randomUUID();
        var now = now();
        jdbc.update("UPDATE settlements SET total_amount=10500 WHERE id=?", id);
        jdbc.update("""
                INSERT INTO settlement_expenses (id, settlement_id, source, source_reference, description, amount,
                    discount, delivery_fee, display_order, created_at, updated_at)
                VALUES (?, ?, 'RECEIPT_OCR', 'synthetic-receipt', '할인과 배달비', 10500, 1000, 1500, 0, ?, ?)
                """, expense, id, now, now);
        jdbc.update("""
                INSERT INTO settlement_items (id, settlement_id, expense_id, name, quantity, amount, display_order, created_at, updated_at)
                VALUES (?, ?, ?, '생성자 품목', 1, 4000, 0, ?, ?), (?, ?, ?, '미선택 품목', 1, 6000, 1, ?, ?)
                """, selectedItem, id, expense, now, now, UUID.randomUUID(), id, expense, now, now);
        jdbc.update("""
                INSERT INTO settlement_item_selections (id, settlement_id, item_id, participant_id, fraction, units, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ONE', 1, ?, ?)
                """, UUID.randomUUID(), id, selectedItem, self, now, now);
        var receive = find(data(request("GET", PATH, widget)), "receivable", id);
        assertThat(receive.path("remainingAmount").longValue()).isEqualTo(6_300);
        assertThat(receive.path("amountUnconfirmed").booleanValue()).isTrue();
        assertThat(receive.path("unpaidParticipantCount").intValue()).isOne();
    }

    @Test
    void submittedParticipantWaitsForReceiptAdjustmentsInsteadOfBeingMarkedPaid() throws Exception {
        UUID id = fixtures.settlement(payer, SettlementStatus.REQUESTED, SettlementAllocationType.ITEM_ALLOCATION);
        fixtures.submitZero(id, fixtures.participant(id, creator));
        var now = now();
        jdbc.update("""
                INSERT INTO settlement_expenses (id, settlement_id, source, source_reference, description, amount,
                    discount, delivery_fee, display_order, created_at, updated_at)
                VALUES (?, ?, 'RECEIPT_OCR', 'synthetic-receipt', '할인 확정 대기', 9000, 1000, 0, 0, ?, ?)
                """, UUID.randomUUID(), id, now, now);
        var pay = find(data(request("GET", PATH, widget)), "payable", id);
        assertThat(pay.path("remainingAmount").isNull()).isTrue();
        assertThat(pay.path("status").stringValue()).isEqualTo("WAITING");
    }

    @Test
    void refreshSharesAccountFetchMatchesPartialIncomeAndPersistsSuccessfulTimestamp() throws Exception {
        UUID first = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "첫 참여자");
        UUID second = receiving(SettlementStatus.AWAITING_DEPOSIT, 2_000, "둘째 참여자");
        bankReturns(List.of(income(400, "첫 참여자 입금"), income(500, "둘째 참여자 입금")));
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", first).path("remainingAmount").longValue()).isEqualTo(600);
        assertThat(find(data, "receivable", second).path("remainingAmount").longValue()).isEqualTo(1_500);
        assertThat(data.path("lastReconciledAt").isNull()).isFalse();
        assertThat(data.path("lastReconciledAt").stringValue())
                .isEqualTo(data(request("GET", PATH, widget)).path("lastReconciledAt").stringValue());
        verify(bank, times(1)).fetch(anyString(), eq("0020"), eq(ACCOUNT), any(), any(), any());
        assertThat(request("POST", PATH + "/refresh", access).statusCode()).isEqualTo(429);
    }

    @Test
    void missingSnapshotDoesNotBlockHealthySettlement() throws Exception {
        UUID missing = receiving(SettlementStatus.REQUESTED, 1_000, "누락 참여자");
        UUID healthy = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "정상 참여자");
        jdbc.update("DELETE FROM settlement_receiving_account_snapshots WHERE settlement_id=?", missing);
        bankReturns(List.of(income(400, "정상 참여자 입금")));
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", healthy).path("remainingAmount").longValue()).isEqualTo(600);
        assertThat(data.path("lastReconciledAt").isNull()).isFalse();
        assertThat(redis.hasKey(key("lock"))).isFalse();
    }

    @Test
    void disconnectedBankReturnsStoredSummaryAndPreservesPreviousSuccess() throws Exception {
        UUID id = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "연결 해제 참여자");
        var previous = now().minusDays(1).withNano(0);
        assertThat(refreshStore.tryAcquire(creator, "previous")).isTrue();
        refreshStore.finish(creator, "previous", previous);
        redis.delete(key("cooldown"));
        when(connections.findActiveByUserId(creator)).thenReturn(Optional.empty());
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", id).path("remainingAmount").longValue()).isEqualTo(1_000);
        assertThat(refreshStore.lastReconciledAt(creator)).contains(previous);
        verifyNoInteractions(bank);
        assertThat(redis.hasKey(key("lock"))).isFalse();
        assertThat(request("POST", PATH + "/refresh", widget).statusCode()).isEqualTo(429);
    }

    @Test
    void closedAccountReturnsStoredSummaryWithoutCallingBank() throws Exception {
        UUID id = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "해지 참여자");
        jdbc.update("UPDATE accounts SET active=false WHERE id=?", accountId);
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", id).path("remainingAmount").longValue()).isEqualTo(1_000);
        assertThat(data.path("lastReconciledAt").isNull()).isTrue();
        verifyNoInteractions(bank);
        assertThat(redis.hasKey(key("lock"))).isFalse();
    }

    @Test
    void stateChangeDuringFetchRollsBackOnlyUnavailableTarget() throws Exception {
        UUID changed = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "중단 참여자");
        UUID healthy = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "정상 참여자");
        // 최신 생성순으로 중단 대상이 먼저 처리되어도 다음 Writer 트랜잭션이 성공해야 한다.
        jdbc.update("UPDATE settlements SET created_at=? WHERE id=?", now().plusMinutes(1), changed);
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?", changed);
            return List.of(income(400, "정상 참여자 입금"));
        });
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", healthy).path("remainingAmount").longValue()).isEqualTo(600);
        assertThat(data.path("lastReconciledAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_deposits WHERE settlement_id=?",
                Long.class, changed)).isZero();
        assertThat(redis.hasKey(key("lock"))).isFalse();
    }

    @Test
    void accountClosedDuringFetchSkipsWriterWithoutClaimingSuccess() throws Exception {
        UUID id = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "해지 참여자");
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            jdbc.update("UPDATE accounts SET active=false WHERE id=?", accountId);
            return List.of(income(400, "해지 참여자 입금"));
        });
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(find(data, "receivable", id).path("remainingAmount").longValue()).isEqualTo(1_000);
        assertThat(data.path("lastReconciledAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_transactions WHERE account_id=?",
                Long.class, accountId)).isZero();
        assertThat(redis.hasKey(key("lock"))).isFalse();
    }

    @Test
    void requestedSettlementsDoNotStealAmbiguousDepositsFromEachOther() throws Exception {
        UUID first = receiving(SettlementStatus.REQUESTED, 1_000, "같은 이름");
        UUID second = receiving(SettlementStatus.REQUESTED, 1_000, "같은 이름");
        bankReturns(List.of(income(1_000, "같은 이름 입금")));
        data(request("POST", PATH + "/refresh", widget));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_deposits WHERE settlement_id IN (?, ?)",
                Long.class, first, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_transactions WHERE account_id=?", Long.class, accountId)).isOne();
        verify(bank, times(1)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void fullPaymentRemovesCompletedSettlementFromReturnedSummary() throws Exception {
        UUID id = receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "완납 참여자");
        bankReturns(List.of(income(1_000, "완납 참여자 입금")));
        var data = data(request("POST", PATH + "/refresh", widget));
        assertThat(data.path("receivable").path("totalCount").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?", String.class, id)).isEqualTo("COMPLETED");
    }

    @Test
    void failedFetchDoesNotClaimSuccessAndStillConsumesCooldown() throws Exception {
        receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "실패 참여자");
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        assertThat(request("POST", PATH + "/refresh", widget).statusCode()).isEqualTo(503);
        assertThat(refreshStore.lastReconciledAt(creator)).isEmpty();
        assertThat(request("POST", PATH + "/refresh", widget).statusCode()).isEqualTo(429);
        verify(bank, times(1)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void concurrentRefreshIsBlockedBeforeAnotherBankCall() throws Exception {
        receiving(SettlementStatus.AWAITING_DEPOSIT, 1_000, "동시 참여자");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of();
        });
        var first = CompletableFuture.supplyAsync(() -> {
            try { return request("POST", PATH + "/refresh", widget); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request("POST", PATH + "/refresh", widget).statusCode()).isEqualTo(429);
        } finally { release.countDown(); }
        assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        verify(bank, times(1)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void redisLeaseRejectsForeignOwnerAndRetainsLastSuccessfulTimeOnFailure() {
        assertThat(refreshStore.tryAcquire(creator, "first")).isTrue();
        assertThat(refreshStore.tryAcquire(creator, "second")).isFalse();
        assertThat(refreshStore.renew(creator, "second")).isFalse();
        refreshStore.finish(creator, "second", now());
        assertThat(refreshStore.lastReconciledAt(creator)).isEmpty();
        assertThat(refreshStore.renew(creator, "first")).isTrue();
        var success = now().withNano(0);
        refreshStore.finish(creator, "first", success);
        assertThat(refreshStore.lastReconciledAt(creator)).contains(success);
        assertThat(refreshStore.tryAcquire(creator, "third")).isFalse();
        redis.delete(key("cooldown"));
        assertThat(refreshStore.tryAcquire(creator, "third")).isTrue();
        refreshStore.finish(creator, "third", null);
        assertThat(refreshStore.lastReconciledAt(creator)).contains(success);
    }

    @Test
    void openApiDocumentsAllThreeOperationsAndWidgetStates() throws Exception {
        var response = request("GET", "/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var doc = json.readTree(response.body());
        for (String path : List.of(PATH, PATH + "/refresh", "/api/v2/auth/widget-token")) {
            var operation = doc.path("paths").path(path).path(path.equals(PATH) ? "get" : "post");
            assertThat(operation.path("security").toString()).contains("bearerAuth");
            assertThat(operation.path("responses").has("200")).isTrue();
            assertThat(operation.path("responses").has("401")).isTrue();
        }
        assertThat(doc.path("paths").path(PATH + "/refresh").path("post").path("responses").has("429")).isTrue();
        assertThat(doc.path("components").path("schemas").toString()).contains("SELECTION_REQUIRED", "widgetToken", "amountUnconfirmed");
    }

    private UUID receiving(SettlementStatus status, long amount, String payerName) {
        UUID id = fixtures.settlement(creator, status, SettlementAllocationType.ITEM_ALLOCATION);
        owned.add(id);
        UUID participant = fixtures.participant(id, payer);
        fixtures.share(id, participant, amount);
        fixtures.submitZero(id, participant);
        jdbc.update("UPDATE settlement_participants SET display_name=? WHERE id=?", payerName, participant);
        var from = now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        jdbc.update("UPDATE settlement_receiving_account_snapshots SET account_number_ciphertext=?, created_at=? WHERE settlement_id=?",
                cipher.encryptSettlementAccountNumber(id, ACCOUNT), from, id);
        accountId = jdbc.queryForObject("SELECT source_account_id FROM settlement_receiving_account_snapshots WHERE settlement_id=?", Long.class, id);
        if (connectionId == null) {
            connectionId = jdbc.queryForObject("""
                    INSERT INTO external_connections (user_id, connected_id_ciphertext, configuration_fingerprint, active, created_at, updated_at)
                    VALUES (?, 'unused-test-ciphertext', ?, TRUE, ?, ?) RETURNING id
                    """, Long.class, creator, "a".repeat(64), from, from);
            jdbc.update("""
                    INSERT INTO accounts (id, user_id, external_connection_id, organization, candidate_key,
                        external_account_key_ciphertext, type, name, masked_number, balance, currency, active,
                        product_code, is_default, display_order, created_at, updated_at)
                    VALUES (?, ?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '검증 계좌', '****6789', 10000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                    """, accountId, creator, connectionId, UUID.randomUUID().toString(),
                    cipher.encryptAccountKey(creator, connectionId, ACCOUNT), from, from);
            when(connections.findActiveByUserId(creator)).thenReturn(Optional.of(new ExternalConnection(connectionId, creator, "test-cid")));
        }
        jdbc.update("UPDATE settlements SET receiving_account_id=? WHERE id=?", accountId, id);
        return id;
    }

    private void bankReturns(List<BankTransactionPort.BankTransaction> transactions) {
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return transactions;
        });
    }

    private BankTransactionPort.BankTransaction income(long amount, String name) {
        return new BankTransactionPort.BankTransaction(AccountTransactionType.INCOME, BigDecimal.valueOf(amount),
                now().minusMinutes(1), name, BigDecimal.valueOf(10_000 + amount));
    }
    private JsonNode find(JsonNode data, String tab, UUID id) {
        for (var row : data.path(tab).path("settlements")) {
            if (row.path("settlementId").stringValue().equals(id.toString())) return row;
        }
        throw new AssertionError("Missing settlement " + id);
    }
    private JsonNode data(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
    }
    private HttpResponse<String> request(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String key(String suffix) { return "settlement:widget:{" + creator + "}:" + suffix; }
    private void clearRefresh() { redis.delete(List.of(key("lock"), key("cooldown"), key("last-reconciled"))); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
