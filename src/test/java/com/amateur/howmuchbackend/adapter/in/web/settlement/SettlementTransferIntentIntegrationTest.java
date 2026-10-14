package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.notification.service.NotificationDeliveryService;
import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.application.port.in.settlement.ProcessSettlementDepositReconciliationJobsUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.*;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.amateur.howmuchbackend.infra.settlement.SettlementTransferReconciliationScheduler;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "howmuch.settlement.transfer-reconciliation.enabled=true",
        "howmuch.settlement.transfer-reconciliation.poll-interval=1d",
        "howmuch.settlement.transfer-reconciliation.batch-size=1",
        "howmuch.settlement.bank-lookup-budget.namespace=issue530-transfer-tests",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
class SettlementTransferIntentIntegrationTest {
    private static final String ACCOUNT = "1002123456789";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokenPort tokens;
    @Autowired WidgetTokenPort widgets;
    @Autowired AuthSessionPort sessions;
    @Autowired LoadUserPort users;
    @Autowired SettlementLinkTokenPort linkTokens;
    @Autowired IssueSettlementParticipantContextPort contexts;
    @Autowired SettlementDepositReconciliationJobStorePort jobs;
    @Autowired SettlementBankLookupBudgetPort budget;
    @Autowired ProcessSettlementDepositReconciliationJobsUseCase worker;
    @Autowired NotificationDeliveryService delivery;
    @Autowired SettlementTransferReconciliationScheduler scheduler;
    @Autowired @Qualifier("settlementTransferReconciliationTaskScheduler") ThreadPoolTaskScheduler transferScheduler;
    @Autowired @Qualifier("taskScheduler") ThreadPoolTaskScheduler commonScheduler;
    @Autowired StringRedisTemplate redis;
    @Autowired CodefSecretCipher cipher;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    @MockitoBean BankTransactionPort bank;
    @MockitoBean ExternalConnectionStorePort connections;
    @MockitoBean BankCatalog catalog;
    @MockitoBean SettlementPushSenderPort sender;
    @MockitoSpyBean NotificationRecorder recorder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final java.util.concurrent.atomic.AtomicInteger budgetActor = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicReference<Instant> instant = new AtomicReference<>();
    private SettlementUnpaidFixtures fixtures;
    private Long creator, payer, outsider, accountId, connectionId;
    private UUID settlementId, participantId, shareId, contextId;
    private String memberToken, creatorToken, foreignToken, widgetToken, sessionId, rawLink, hash;
    private LocalDateTime start;

    @BeforeEach void setUp() {
        start=LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        instant.set(start.toInstant(ZoneOffset.UTC));
        when(clock.instant()).thenAnswer(call -> instant.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        fixtures=new SettlementUnpaidFixtures(jdbc);
        creator=fixtures.user(); payer=fixtures.user(); outsider=fixtures.user();
        sessionId=UUID.randomUUID().toString();
        creatorToken=login(creator); memberToken=login(payer); foreignToken=login(outsider);
        widgetToken=widgets.createWidgetToken(payer, sessionId, start.plusDays(1));
        settlementId=fixtures.settlement(creator, SettlementStatus.AWAITING_DEPOSIT, SettlementAllocationType.DUTCH_PAY);
        fixtures.participant(settlementId, creator);
        participantId=fixtures.participant(settlementId, payer);
        shareId=fixtures.share(settlementId, participantId, 1000);
        jdbc.update("UPDATE settlements SET title='예약 검증', total_amount=1000 WHERE id=?", settlementId);
        jdbc.update("UPDATE settlement_participants SET display_name='입금자' WHERE id=?", participantId);
        jdbc.update("UPDATE users SET fcm_token=? WHERE id=?", "synthetic-provider-target-"+creator, creator);
        accountId=jdbc.queryForObject("SELECT source_account_id FROM settlement_receiving_account_snapshots WHERE settlement_id=?", Long.class, settlementId);
        var from=start.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        jdbc.update("UPDATE settlement_receiving_account_snapshots SET account_number_ciphertext=?, created_at=? WHERE settlement_id=?",
                cipher.encryptSettlementAccountNumber(settlementId, ACCOUNT), from, settlementId);
        connectionId=jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)
                VALUES (?,'synthetic',?,true,?,?) RETURNING id
                """, Long.class, creator, UUID.randomUUID().toString(), from, from);
        jdbc.update("""
                INSERT INTO accounts(id,user_id,external_connection_id,organization,candidate_key,external_account_key_ciphertext,
                    type,name,masked_number,balance,currency,active,product_code,is_default,display_order,created_at,updated_at)
                VALUES (?,?,?,'0020',?,?,'DEPOSIT_TRUST','검증 계좌','****6789',10000,'KRW',true,'11',true,0,?,?)
                """, accountId,creator,connectionId,UUID.randomUUID().toString(),cipher.encryptAccountKey(creator,connectionId,ACCOUNT),from,from);
        jdbc.update("UPDATE settlements SET receiving_account_id=? WHERE id=?",accountId,settlementId);
        when(connections.findActiveByUserId(creator)).thenReturn(Optional.of(new ExternalConnection(connectionId,creator,"synthetic-cid")));
        rawLink=linkTokens.issue(start).rawToken(); hash=linkTokens.hash(rawLink);
        jdbc.update("""
                INSERT INTO settlement_links(id,settlement_id,token_hash,status,expires_at,created_at,updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(),settlementId,hash,start.plusHours(1),start.minusMinutes(1),start);
        contextId=contexts.issue(hash,settlementId,participantId,start).id();
        bankReturns(List.of());
    }

    @AfterEach void tearDown() {
        for (Long user : List.of(creator,payer,outsider)) {
            sessions.deleteByUserId(user);
            redis.delete(redis.keys("settlement:bank-budget:{issue530-transfer-tests}:*"));
            redis.delete(redis.keys("settlement:widget:{"+user+"}:*"));
            jdbc.update("DELETE FROM user_notifications WHERE recipient_user_id=?",user);
        }
        redis.delete("settlement:link-participant-context:"+contextId);
        redis.delete("settlement:link-participant-context-index:"+hash+":"+participantId);
        jdbc.update("DELETE FROM settlement_deposits WHERE settlement_id=?",settlementId);
        jdbc.update("DELETE FROM account_transactions WHERE account_id=?",accountId);
        jdbc.update("DELETE FROM settlements WHERE id=?",settlementId);
        jdbc.update("DELETE FROM accounts WHERE id=?",accountId);
        jdbc.update("DELETE FROM external_connections WHERE id=?",connectionId);
        fixtures.close();
    }

    @Test void signal_authenticationAndOwnership_rejectsInvalidActors() throws Exception {
        assertThat(post(memberPath(),null,null).statusCode()).isEqualTo(401);
        assertThat(post(memberPath(),widgetToken,null).statusCode()).isEqualTo(401);
        assertThat(post(memberPath(),foreignToken,null).statusCode()).isEqualTo(403);
        assertThat(post(memberPath(),creatorToken,null).statusCode()).isEqualTo(409);
        assertThat(post(linkPath(),null,UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(post(linkPath(),null,null).statusCode()).isEqualTo(400);
        sessions.deleteByUserId(payer);
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(401);
        assertThat(jobCount()).isZero(); verifyNoInteractions(bank);
    }

    @Test void signal_linkAndContextExpiry_rejectsInvalidCapabilities() throws Exception {
        jdbc.update("UPDATE settlement_links SET status='REVOKED' WHERE settlement_id=?",settlementId);
        assertThat(post(linkPath(),null,contextId).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE settlement_links SET status='ACTIVE' WHERE settlement_id=?",settlementId);
        var foreignContext=contexts.issue("f".repeat(64),settlementId,participantId,start);
        try { assertThat(post(linkPath(),null,foreignContext.id()).statusCode()).isEqualTo(404); }
        finally {
            redis.delete("settlement:link-participant-context:"+foreignContext.id());
            redis.delete("settlement:link-participant-context-index:"+"f".repeat(64)+":"+participantId);
        }
        at(1801);
        assertThat(post(linkPath(),null,contextId).statusCode()).isEqualTo(404);
        assertThat(jobCount()).isZero(); verifyNoInteractions(bank);
    }

    @Test void signal_paidMissingShareAndInactiveState_doesNotQueue() throws Exception {
        for (String state : List.of("DRAFT","SUSPENDED","COMPLETED")) {
            jdbc.update("UPDATE settlements SET status=? WHERE id=?",state,settlementId);
            assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(409);
        }
        jdbc.update("UPDATE settlements SET status='REQUESTED' WHERE id=?",settlementId);
        jdbc.update("DELETE FROM settlement_shares WHERE id=?",shareId);
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(409);
        shareId=fixtures.share(settlementId,participantId,1000);
        fixtures.deposit(creator,settlementId,shareId,1000);
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(409);
        fixtures.markDeleted(settlementId);
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(404);
        assertThat(jobCount()).isZero(); verifyNoInteractions(bank);
    }

    @Test void signal_memberAndLinkTaps_coalescesWithoutPostponing() throws Exception {
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(202);
        var first=jobRow();
        at(5);
        assertThat(post(linkPath(),null,contextId).statusCode()).isEqualTo(202);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var calls=new ArrayList<Future<Integer>>();
            for(int i=0;i<12;i++) calls.add(pool.submit(() -> post(memberPath(),memberToken,null).statusCode()));
            for(var call:calls) assertThat(call.get(15,TimeUnit.SECONDS)).isEqualTo(202);
        }
        assertThat(jobCount()).isOne();
        assertThat(jobRow().get("next_attempt_at")).isEqualTo(first.get("next_attempt_at"));
        assertThat(jobRow().get("expires_at")).isEqualTo(first.get("expires_at"));
        assertThat(jobRow().get("attempt_count")).isEqualTo(0);
        assertThat(worker.processDue(10)).isZero(); verifyNoInteractions(bank);
        at(30); assertThat(worker.processDue(10)).isOne();
        assertThat(jobStatus()).isEqualTo("RETRY");
        verify(bank,times(1)).fetch(anyString(),anyString(),anyString(),any(),any(),any());
    }

    @Test void reconcile_partialDeposit_commitsNotificationAndUpdatedWidget() throws Exception {
        bankReturns(List.of(income(400,"partial")));
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(202);
        at(30); scheduler.processDue();
        assertThat(depositCount()).isOne(); assertThat(jobStatus()).isEqualTo("RETRY");
        var notification=jdbc.queryForMap("SELECT * FROM user_notifications WHERE recipient_user_id=?",creator);
        assertThat(notification.get("notification_type")).isEqualTo("SETTLEMENT_DEPOSIT_CONFIRMED");
        assertThat(notification.get("message").toString()).contains("예약","입금자","600원");
        assertThat(notification.get("target_id")).isEqualTo(settlementId.toString());
        var widget=request("GET","/api/v2/settlements/widget-summary",creatorToken,null);
        assertThat(widget.statusCode()).isEqualTo(200);
        assertThat(json.readTree(widget.body()).path("data").path("receivable").path("totalAmount").longValue()).isEqualTo(600);
        // 기존 수동 경로에서 같은 은행 거래를 다시 읽어도 중복 입금·알림을 만들지 않는다.
        assertThat(post("/api/v2/settlements/"+settlementId+"/deposits/reconcile",creatorToken,null).statusCode()).isEqualTo(200);
        assertThat(depositCount()).isOne(); assertThat(notificationCount()).isOne();
        UUID deliveryId=jdbc.queryForObject("SELECT id FROM settlement_notification_outbox WHERE notification_id=?",UUID.class,notification.get("id"));
        when(sender.sendNotification(anyString(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return SettlementPushSenderPort.SendResult.SENT;
        });
        delivery.deliver(deliveryId,0); delivery.deliver(deliveryId,0);
        var push=org.mockito.ArgumentCaptor.forClass(SettlementPushSenderPort.NotificationMessage.class);
        verify(sender,times(1)).sendNotification(anyString(),push.capture());
        assertThat(push.getValue().settlementId()).isEqualTo(settlementId);
        assertThat(push.getValue().type()).isEqualTo("SETTLEMENT_DEPOSIT_CONFIRMED");
    }

    @Test void reconcile_fullDeposit_sendsOnlyCompletionToCreator() throws Exception {
        bankReturns(List.of(income(1000,"full"))); post(memberPath(),memberToken,null);
        at(30); worker.processDue(1);
        assertThat(jobStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?",String.class,settlementId)).isEqualTo("COMPLETED");
        var notification=jdbc.queryForMap("SELECT * FROM user_notifications WHERE recipient_user_id=?",creator);
        assertThat(notification.get("notification_type")).isEqualTo("SETTLEMENT_COMPLETED");
        assertThat(notification.get("title")).isEqualTo("모두 받았어요");
        assertThat(notification.get("message").toString()).contains("입금자","0원");
        assertThat(notificationCount()).isEqualTo(2);
        assertThat(worker.processDue(10)).isZero();
    }

    @Test void reconcile_requestedShare_allowsPaymentBeforeAllSubmissions() throws Exception {
        jdbc.update("UPDATE settlements SET status='REQUESTED', allocation_type='ITEM_ALLOCATION' WHERE id=?",settlementId);
        bankReturns(List.of(income(1000,"requested"))); post(memberPath(),memberToken,null);
        at(30); worker.processDue(1);
        assertThat(depositCount()).isOne(); assertThat(jobStatus()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?",String.class,settlementId)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT message FROM user_notifications WHERE recipient_user_id=?",String.class,creator))
                .contains("현재 확정된 몫");
    }

    @Test void reconcile_unpaid_stopsAfterThreeAttempts() throws Exception {
        post(memberPath(),memberToken,null);
        at(30); worker.processDue(10); assertThat(jobStatus()).isEqualTo("RETRY");
        at(119); assertThat(worker.processDue(10)).isZero();
        at(120); worker.processDue(10); assertThat(jobStatus()).isEqualTo("RETRY");
        at(420); worker.processDue(10); assertThat(jobStatus()).isEqualTo("EXHAUSTED");
        assertThat(jobRow().get("attempt_count")).isEqualTo(3);
        at(1000); assertThat(worker.processDue(10)).isZero();
        verify(bank,times(3)).fetch(anyString(),anyString(),anyString(),any(),any(),any());
        assertThat(notificationCount()).isZero();
    }

    @Test void reconcile_providerError_keepsFailureDistinctFromUnpaid() throws Exception {
        when(bank.fetch(anyString(),anyString(),anyString(),any(),any(),any()))
                .thenThrow(ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        post(memberPath(),memberToken,null); at(30); worker.processDue(1);
        assertThat(jobStatus()).isEqualTo("RETRY");
        assertThat(jobRow().get("last_failure_code")).isEqualTo("PROVIDER_ERROR");
        assertThat(depositCount()).isZero(); assertThat(notificationCount()).isZero();
    }

    @Test void reconcile_suspendedAfterSignal_cancelsWithoutBankCall() throws Exception {
        post(memberPath(),memberToken,null);
        jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?",settlementId);
        at(30); worker.processDue(1);
        assertThat(jobStatus()).isEqualTo("CANCELLED"); verifyNoInteractions(bank);
    }

    @Test void reconcile_notificationFailure_rollsBackDepositAndJobResult() throws Exception {
        bankReturns(List.of(income(400,"rollback"))); post(memberPath(),memberToken,null);
        doThrow(new IllegalStateException("synthetic failure")).when(recorder).record(any());
        at(30); worker.processDue(1);
        assertThat(jobStatus()).isEqualTo("RETRY"); assertThat(depositCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_transactions WHERE account_id=?",Long.class,accountId)).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test void workers_concurrentClaimAndProcessingTap_shareOneBankCall() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(bank.fetch(anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return List.of();
        });
        post(memberPath(),memberToken,null); at(30);
        var first=CompletableFuture.supplyAsync(() -> worker.processDue(1));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            var claim=jobRow(); assertThat(worker.processDue(1)).isZero();
            var response=post(linkPath(),null,contextId); assertThat(response.statusCode()).isEqualTo(202);
            assertThat(json.readTree(response.body()).path("data").path("status").stringValue()).isEqualTo("PROCESSING");
            assertThat(jobRow().get("lease_token")).isEqualTo(claim.get("lease_token"));
            assertThat(jobRow().get("generation")).isEqualTo(claim.get("generation"));
        } finally { release.countDown(); }
        assertThat(first.get(20,TimeUnit.SECONDS)).isOne();
        verify(bank,times(1)).fetch(anyString(),anyString(),anyString(),any(),any(),any());
    }

    @Test void worker_expiredClaim_cannotWriteOrNotifyReplacement() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(bank.fetch(anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return List.of(income(400,"stale"));
        });
        post(memberPath(),memberToken,null); at(30);
        var first=CompletableFuture.supplyAsync(() -> worker.processDue(1));
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue(); at(601);
            assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(202);
            assertThat(jobRow().get("generation")).isEqualTo(2L);
        } finally { release.countDown(); }
        first.get(20,TimeUnit.SECONDS);
        assertThat(jobStatus()).isEqualTo("PENDING"); assertThat(depositCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test void claim_expiredLease_recoversWithoutResettingBudget() throws Exception {
        post(memberPath(),memberToken,null); at(30);
        var first=jobs.claimDue(now(),now().plusSeconds(5)).orElseThrow();
        at(36); var recovered=jobs.claimDue(now(),now().plusSeconds(30)).orElseThrow();
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(jobs.renew(first,now(),now().plusMinutes(6))).isFalse();
        assertThat(jobs.finish(first,SettlementDepositReconciliationStatus.SUCCEEDED,null,null,now())).isFalse();
        assertThat(jobs.finish(recovered,SettlementDepositReconciliationStatus.EXHAUSTED,null,"PROVIDER_ERROR",now())).isTrue();
        assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(429);
        at(601); assertThat(post(memberPath(),memberToken,null).statusCode()).isEqualTo(202);
        assertThat(jobRow().get("generation")).isEqualTo(2L);
        assertThat(jobRow().get("attempt_count")).isEqualTo(0);
    }

    @Test void budget_automaticLimit_preservesTwentyManualCalls() throws Exception {
        for(int i=0;i<80;i++) budget.reserve(creator,true,now(),100,80);
        post(memberPath(),memberToken,null); at(30); worker.processDue(1);
        assertThat(jobStatus()).isEqualTo("EXHAUSTED");
        assertThat(jobRow().get("last_failure_code")).isEqualTo("QUOTA_EXHAUSTED"); verifyNoInteractions(bank);
        for(int i=0;i<20;i++) budget.reserve(creator,false,now(),100,80);
        assertThatThrownBy(() -> budget.reserve(creator,false,now(),100,80))
                .isInstanceOfSatisfying(ApplicationException.class,e -> assertThat(e.getStatusCode()).isEqualTo(CommonStatusCode.TOO_MANY_REQUEST));
    }

    @Test void budget_concurrentReservations_neverExceedsDailyLimit() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var reservations=new ArrayList<Future<Boolean>>();
            for(int i=0;i<120;i++) reservations.add(pool.submit(() -> {
                try { budget.reserve(iUser(),false,now(),100,80); return true; }
                catch(ApplicationException e) { assertThat(e.getStatusCode()).isEqualTo(CommonStatusCode.TOO_MANY_REQUEST); return false; }
            }));
            int accepted=0; for(var result:reservations) if(result.get(10,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(100);
        }
    }

    @Test void budget_kstDayRollover_startsNewDayCounter() {
        var oldDay=start.withHour(14).withMinute(59).withSecond(59);
        for(int i=0;i<100;i++) budget.reserve(creator,false,oldDay,100,80);
        assertThatThrownBy(() -> budget.reserve(creator,false,oldDay,100,80)).isInstanceOf(ApplicationException.class);
        budget.reserve(creator,false,oldDay.plusSeconds(1),100,80);
        var keys=redis.keys("settlement:bank-budget:{issue530-transfer-tests}:global:*");
        assertThat(keys).hasSize(2);
        keys.forEach(key -> assertThat(redis.getExpire(key)).isPositive());
    }

    @Test void openApi_transferSignalDocuments_contractAndNewType() throws Exception {
        var response=request("GET","/v3/api-docs",null,null); assertThat(response.statusCode()).isEqualTo(200);
        var paths=json.readTree(response.body()).path("paths");
        assertThat(paths.path("/api/v2/settlements/{settlementId}/my-transfer-intent").path("post").path("responses").has("202")).isTrue();
        assertThat(paths.path("/api/v2/settlement-links/{token}/transfer-intent").path("post").path("parameters").toString())
                .contains("Link-Participant-Context");
        assertThat(response.body()).contains("SETTLEMENT_DEPOSIT_CONFIRMED");
    }

    private Long iUser() { return budgetActor.getAndIncrement() % 2 == 0 ? creator : outsider; }
    @Test void scheduler_slowBankRequest_keepsCommonTasksRunnable() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var commonRan=new CountDownLatch(1);
        when(bank.fetch(anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).startsWith("settlement-transfer-reconciliation-");
            entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return List.of();
        });
        post(memberPath(),memberToken,null); at(30);
        var task=transferScheduler.schedule(scheduler::processDue,Instant.now());
        try {
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            commonScheduler.schedule(commonRan::countDown,Instant.now());
            assertThat(commonRan.await(3,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        task.get(20,TimeUnit.SECONDS);
        assertThat(jobStatus()).isEqualTo("RETRY");
    }

    private String login(Long userId) {
        sessions.save(new AuthSession(userId,sessionId,"synthetic-refresh",start.plusDays(3)));
        return tokens.createAccessToken(users.findById(userId).orElseThrow(),sessionId);
    }
    private void at(long seconds) { instant.set(start.plusSeconds(seconds).toInstant(ZoneOffset.UTC)); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(instant.get(),ZoneOffset.UTC); }
    private String memberPath() { return "/api/v2/settlements/"+settlementId+"/my-transfer-intent"; }
    private String linkPath() { return "/api/v2/settlement-links/"+rawLink+"/transfer-intent"; }
    private HttpResponse<String> post(String path,String token,UUID context) throws Exception { return request("POST",path,token,context); }
    private HttpResponse<String> request(String method,String path,String token,UUID context) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                .method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(context!=null) builder.header("Link-Participant-Context",context.toString());
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private void bankReturns(List<BankTransactionPort.BankTransaction> transactions) {
        when(bank.fetch(anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return transactions;
        });
    }
    private BankTransactionPort.BankTransaction income(long amount,String salt) {
        return new BankTransactionPort.BankTransaction(AccountTransactionType.INCOME,BigDecimal.valueOf(amount),
                start.minusSeconds(1),"입금자 "+salt,BigDecimal.valueOf(10000+amount));
    }
    private Long jobCount() { return jdbc.queryForObject("SELECT count(*) FROM settlement_deposit_reconciliation_jobs WHERE settlement_id=?",Long.class,settlementId); }
    private Long depositCount() { return jdbc.queryForObject("SELECT count(*) FROM settlement_deposits WHERE settlement_id=?",Long.class,settlementId); }
    private Long notificationCount() { return jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE settlement_id=?",Long.class,settlementId); }
    private String jobStatus() { return jobRow().get("status").toString(); }
    private Map<String,Object> jobRow() { return jdbc.queryForMap("SELECT * FROM settlement_deposit_reconciliation_jobs WHERE settlement_id=?",settlementId); }
}
