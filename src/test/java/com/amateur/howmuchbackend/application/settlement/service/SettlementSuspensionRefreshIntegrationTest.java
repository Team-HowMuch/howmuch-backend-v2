package com.amateur.howmuchbackend.application.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@TestPropertySource(
        properties = {
            "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
            "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@DisplayName("정산 중단 입금 재조회 transaction 경계 통합")
class SettlementSuspensionRefreshIntegrationTest {
    private static final String ORGANIZATION = "0020";
    private static final String ACCOUNT_NUMBER = "1002123456789";

    @Autowired private SettlementService settlementService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodefSecretCipher cipher;

    @MockitoBean private CurrentActorPort currentActorPort;
    @MockitoBean private ExternalConnectionStorePort connectionStorePort;
    @MockitoBean private BankTransactionPort bankTransactionPort;
    @MockitoBean private BankCatalog bankCatalog;

    @MockitoBean
    private com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService
            notifications;

    private Long creatorId;
    private Long payerId;
    private Long connectionId;
    private Long accountId;
    private UUID settlementId;
    private UUID payerParticipantId;
    private LocalDateTime createdAt;
    private AtomicReference<Boolean> fetchHadTransaction;

    @BeforeEach
    void setUp() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1).withNano(0);
        creatorId = insertUser("suspend-refresh-creator");
        payerId = insertUser("suspend-refresh-payer");
        connectionId = insertConnection(creatorId);
        accountId = insertAccount(creatorId, connectionId);
        settlementId = UUID.randomUUID();
        payerParticipantId = UUID.randomUUID();
        insertSettlementAndPayableShare();

        fetchHadTransaction = new AtomicReference<>();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));
        when(connectionStorePort.findActiveByUserId(creatorId))
                .thenReturn(
                        java.util.Optional.of(
                                new ExternalConnection(connectionId, creatorId, "connected-id")));
        when(bankCatalog.requiresAccountCredentials(ORGANIZATION)).thenReturn(false);
        when(bankTransactionPort.fetch(
                        anyString(), eq(ORGANIZATION), eq(ACCOUNT_NUMBER), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            fetchHadTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return java.util.List.of(
                                    new BankTransactionPort.BankTransaction(
                                            com.amateur.howmuchbackend.domain.account
                                                    .AccountTransactionType.INCOME,
                                            BigDecimal.valueOf(250),
                                            createdAt.plusMinutes(1),
                                            "민선영 입금",
                                            BigDecimal.valueOf(10250)));
                        });
    }

    @AfterEach
    void tearDown() {
        if (settlementId != null) {
            jdbcTemplate.update(
                    "DELETE FROM settlement_deposits WHERE settlement_id = ?", settlementId);
            jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        }
        if (accountId != null) {
            jdbcTemplate.update(
                    "DELETE FROM account_monthly_summaries WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountId);
        }
        if (connectionId != null) {
            jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        }
        if (creatorId != null || payerId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", creatorId, payerId);
        }
    }

    @Test
    @DisplayName("CODEF는 proxy 기반 refresh 흐름에서도 DB 트랜잭션 밖에서 호출되고 발견 입금은 중단 거절 뒤에도 보존된다")
    void
            suspendWithMatchedPartialIncome_fetchesOutsideTransactionAndPersistsDepositBeforeRejecting() {
        Throwable failure = catchThrowable(() -> settlementService.suspend(settlementId));
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(countTransactions()).isEqualTo(1L);
        assertThat(countDeposits()).isEqualTo(1L);
        assertThat(failure)
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(
                                                SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));
        assertThat(status()).isEqualTo("REQUESTED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REQUESTED", "AWAITING_DEPOSIT"})
    @DisplayName("실제 입금이 없는 제출 완료 정산은 두 요청 상태 모두에서 중단한다")
    void submittedWithoutIncome_canSuspend(String initialStatus) {
        jdbcTemplate.update(
                "UPDATE settlements SET status=? WHERE id=?", initialStatus, settlementId);
        bankReturns(List.of());
        assertThat(settlementService.suspend(settlementId).status().name()).isEqualTo("SUSPENDED");
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(status()).isEqualTo("SUSPENDED");
        assertThat(countDeposits()).isZero();
    }

    @Test
    @DisplayName("완납 발견은 완료 상태와 입금 매칭을 커밋한 뒤 중단을 거절한다")
    void fullIncome_commitsCompletionAndRejectsSuspension() {
        jdbcTemplate.update(
                "UPDATE settlements SET status='AWAITING_DEPOSIT' WHERE id=?", settlementId);
        bankReturns(List.of(income(1000, "민선영 입금")));
        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(
                                                SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(countTransactions()).isEqualTo(1L);
        assertThat(countDeposits()).isEqualTo(1L);
        assertThat(status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("귀속 불명 양수 거래도 저장하되 정산을 중단하지 않는다")
    void unattributedIncome_isRetainedAndBlocksSuspension() {
        bankReturns(List.of(income(250, "귀속 불명 입금")));
        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(
                                                SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(countTransactions()).isEqualTo(1L);
        assertThat(countDeposits()).isZero();
        assertThat(status()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("CODEF 추가 인증 오류는 미납으로 오인하지 않고 상태를 유지한다")
    void additionalAuthenticationFailure_leavesStateUnchanged() {
        when(bankTransactionPort.fetch(
                        anyString(), eq(ORGANIZATION), eq(ACCOUNT_NUMBER), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            fetchHadTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            throw ApplicationException.of(
                                    com.amateur.howmuchbackend.domain.external.status
                                            .ExternalConnectionStatusCode
                                            .CODEF_ADDITIONAL_AUTH_REQUIRED);
                        });
        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(
                                                com.amateur.howmuchbackend.domain.external.status
                                                        .ExternalConnectionStatusCode
                                                        .CODEF_ADDITIONAL_AUTH_REQUIRED));
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(countTransactions()).isZero();
        assertThat(countDeposits()).isZero();
        assertThat(status()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("비생성자는 은행 조회 전에 거절된다")
    void otherUserCannotSuspendOrFetchBankData() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(payerId));
        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(bankTransactionPort);
        assertThat(status()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("은행 조회 중 Share가 변경되면 최종 중단은 거절된다")
    void shareChangedDuringFetch_rejectsFinalSuspension() {
        when(bankTransactionPort.fetch(
                        anyString(), eq(ORGANIZATION), eq(ACCOUNT_NUMBER), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            fetchHadTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            jdbcTemplate.update(
                                    "UPDATE settlement_shares SET amount=900 WHERE settlement_id=?",
                                    settlementId);
                            return List.of();
                        });
        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        exception ->
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(
                                                com.amateur.howmuchbackend.common.exception.status
                                                        .CommonStatusCode.CONCURRENT_UPDATE));
        assertThat(fetchHadTransaction).hasValue(false);
        assertThat(status()).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("미납 중단 후 재개는 기존 Share·제출을 초기화해 재제출을 준비한다")
    void resumeAfterUnpaidSuspension_clearsOnlyUnpaidSharesAndSubmissions() {
        bankReturns(List.of());
        settlementService.suspend(settlementId);
        assertThat(settlementService.resume(settlementId).status().name()).isEqualTo("REQUESTED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM settlement_shares WHERE settlement_id=?",
                                Long.class,
                                settlementId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM settlement_item_submissions WHERE"
                                        + " settlement_id=?",
                                Long.class,
                                settlementId))
                .isZero();
    }

    private void bankReturns(List<BankTransactionPort.BankTransaction> transactions) {
        when(bankTransactionPort.fetch(
                        anyString(), eq(ORGANIZATION), eq(ACCOUNT_NUMBER), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            fetchHadTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return transactions;
                        });
    }

    private BankTransactionPort.BankTransaction income(long amount, String name) {
        return new BankTransactionPort.BankTransaction(
                com.amateur.howmuchbackend.domain.account.AccountTransactionType.INCOME,
                BigDecimal.valueOf(amount),
                createdAt.plusMinutes(2),
                name,
                BigDecimal.valueOf(10000 + amount));
    }

    private String status() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id=?", String.class, settlementId);
    }

    private long countTransactions() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM account_transactions WHERE account_id=?",
                Long.class,
                accountId);
    }

    private long countDeposits() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM settlement_deposits WHERE settlement_id=?",
                Long.class,
                settlementId);
    }

    private void insertSettlementAndPayableShare() {
        LocalDateTime updatedAt = createdAt.plusMinutes(1);
        jdbcTemplate.update(
                """
                INSERT INTO settlements (
                    id, creator_id, title, total_amount, allocation_type,
                    receiving_account_id, status, version, created_at, updated_at
                ) VALUES (?, ?, '재조회 정산', 1000, 'ITEM_ALLOCATION', ?, 'REQUESTED', 0, ?, ?)
                """,
                settlementId,
                creatorId,
                accountId,
                createdAt,
                updatedAt);
        UUID creatorParticipantId = UUID.randomUUID();
        insertParticipant(creatorParticipantId, creatorId, "최수아", 0, updatedAt);
        insertParticipant(payerParticipantId, payerId, "민선영", 1, updatedAt);
        jdbcTemplate.update(
                """
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                ) VALUES (?, ?, ?, ?, '최수아', ?)
                """,
                settlementId,
                accountId,
                ORGANIZATION,
                cipher.encryptSettlementAccountNumber(settlementId, ACCOUNT_NUMBER),
                createdAt);
        jdbcTemplate.update(
                """
                INSERT INTO settlement_shares (id, settlement_id, payer_participant_id, amount, created_at)
                VALUES (?, ?, ?, 1000, ?)
                """,
                UUID.randomUUID(),
                settlementId,
                payerParticipantId,
                updatedAt);
        jdbcTemplate.update(
                """
                INSERT INTO settlement_item_submissions (id, settlement_id, participant_id, submitted_at)
                VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                settlementId,
                payerParticipantId,
                updatedAt);
    }

    private void insertParticipant(
            UUID id, Long userId, String name, int order, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO settlement_participants (
                    id, settlement_id, source, linked_user_id, display_name,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, 'REGISTERED_USER', ?, ?, ?, ?, ?)
                """,
                id,
                settlementId,
                userId,
                name,
                order,
                now,
                now);
    }

    private Long insertConnection(Long userId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO external_connections (
                    user_id, connected_id_ciphertext, configuration_fingerprint,
                    active, created_at, updated_at
                ) VALUES (?, 'unused-test-ciphertext', ?, TRUE, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                "a".repeat(64),
                createdAt,
                createdAt);
    }

    private Long insertAccount(Long userId, Long externalConnectionId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO accounts (
                    user_id, external_connection_id, organization, candidate_key,
                    external_account_key_ciphertext, type, name, masked_number,
                    balance, currency, active, product_code, is_default,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'DEPOSIT_TRUST', '수금 계좌', '****6789',
                          100000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                externalConnectionId,
                ORGANIZATION,
                UUID.randomUUID().toString(),
                cipher.encryptAccountKey(userId, externalConnectionId, ACCOUNT_NUMBER),
                createdAt,
                createdAt);
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (
                    identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, required_terms_agreed_at,
                    created_at, updated_at
                ) VALUES (?, '테스트 사용자', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                prefix + "-" + unique,
                prefix.substring(0, 3) + "_" + unique.replace("-", "").substring(0, 8),
                "test-" + unique.substring(0, 12),
                createdAt,
                createdAt,
                createdAt);
    }
}
