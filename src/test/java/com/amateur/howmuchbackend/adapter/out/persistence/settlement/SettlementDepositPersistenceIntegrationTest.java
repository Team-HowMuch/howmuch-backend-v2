package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@DisplayName("정산 입금 후보 영속 통합")
class SettlementDepositPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 0, 0);
    private static final String ACCOUNT_NUMBER = "1002123456789";

    @Autowired
    private SettlementDepositPersistenceAdapter adapter;

    @Autowired
    private CodefSecretCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long payerId;
    private Long sourceAccountId;
    private Long reconnectedAccountId;
    private List<UUID> settlementIds;

    @BeforeEach
    void setUp() {
        creatorId = insertUser("creator");
        payerId = insertUser("payer");
        sourceAccountId = Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        reconnectedAccountId = Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        insertAccountRecord(sourceAccountId);
        insertAccountRecord(reconnectedAccountId);

        settlementIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        insertAwaitingSettlement(settlementIds.get(0), sourceAccountId);
        insertAwaitingSettlement(settlementIds.get(1), reconnectedAccountId);
    }

    private void insertAccountRecord(Long accountId) {
        jdbcTemplate.update("""
                INSERT INTO account_records (
                    id, user_id, organization, type, name, masked_number, currency,
                    linked_at, deleted_at
                ) VALUES (?, ?, '0020', 'DEPOSIT_TRUST', '수금 계좌', '****6789', 'KRW', ?, NULL)
                """, accountId, creatorId, NOW);
    }

    @AfterEach
    void tearDown() {
        if (settlementIds != null) {
            settlementIds.forEach(settlementId ->
                    jdbcTemplate.update("DELETE FROM settlement_deposits WHERE settlement_id = ?", settlementId));
            jdbcTemplate.update(
                    "DELETE FROM account_transactions WHERE account_id IN (?, ?)",
                    sourceAccountId,
                    reconnectedAccountId
            );
            settlementIds.forEach(settlementId ->
                    jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId));
        }
        if (sourceAccountId != null) {
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", sourceAccountId);
        }
        if (reconnectedAccountId != null) {
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", reconnectedAccountId);
        }
        if (payerId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", payerId);
        }
        if (creatorId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
        }
    }

    @Test
    @DisplayName("삭제 후 재연결된 같은 수금 계좌의 미완료 Share도 함께 조회한다")
    void depositCandidates_reconnectedAccount_includesPendingShares() {
        var candidates = adapter.findUnfinishedPayableCandidatesByReceivingAccount(
                creatorId,
                sourceAccountId,
                "0020",
                ACCOUNT_NUMBER
        );

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(candidate -> candidate.settlementId())
                .containsExactlyInAnyOrderElementsOf(settlementIds);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.payerName()).isEqualTo("민선영");
            assertThat(candidate.remainingAmount()).isEqualTo(6_000);
            assertThat(candidate.snapshotCreatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("재연결 전 계좌에서 정산에 연결한 외부 거래 Key를 조회한다")
    void depositTransactions_reconnectedAccount_externalKey_loaded() {
        String externalTransactionKey = "a".repeat(64);
        Long transactionId = jdbcTemplate.queryForObject("""
                        INSERT INTO account_transactions (
                            account_id, external_transaction_key, transaction_type, amount,
                            transacted_at, description, after_balance, created_at, updated_at
                        ) VALUES (?, ?, 'INCOME', 6000, ?, '민선영 입금', 106000, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                sourceAccountId,
                externalTransactionKey,
                NOW.plusHours(1),
                NOW,
                NOW
        );
        UUID oldSettlementId = settlementIds.get(0);
        UUID shareId = jdbcTemplate.queryForObject(
                "SELECT id FROM settlement_shares WHERE settlement_id = ?",
                UUID.class,
                oldSettlementId
        );
        jdbcTemplate.update("""
                INSERT INTO settlement_deposits (
                    id, settlement_id, share_id, source_account_id,
                    account_transaction_id, matched_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                oldSettlementId,
                shareId,
                sourceAccountId,
                transactionId,
                NOW.plusHours(1)
        );

        Set<String> matchedKeys = adapter.findMatchedExternalTransactionKeysByReceivingAccount(
                creatorId,
                reconnectedAccountId,
                "0020",
                ACCOUNT_NUMBER,
                Set.of(externalTransactionKey)
        );

        assertThat(matchedKeys).containsExactly(externalTransactionKey);
    }

    @Test
    @DisplayName("정산 상세 조회를 위해 Share별 실제 입금액을 합산한다")
    void sumsConfirmedAmountByShare() {
        UUID settlementId = settlementIds.get(0);
        UUID shareId = jdbcTemplate.queryForObject(
                "SELECT id FROM settlement_shares WHERE settlement_id = ?",
                UUID.class,
                settlementId
        );
        insertDeposit(settlementId, shareId, 2_500, "b".repeat(64));
        insertDeposit(settlementId, shareId, 3_500, "c".repeat(64));

        var amounts = adapter.findConfirmedShareAmountsBySettlementId(settlementId);

        assertThat(amounts).containsExactly(
                new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(shareId, 6_000)
        );
    }

    private void insertDeposit(
            UUID settlementId,
            UUID shareId,
            long amount,
            String externalTransactionKey
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                        INSERT INTO account_transactions (
                            account_id, external_transaction_key, transaction_type, amount,
                            transacted_at, description, after_balance, created_at, updated_at
                        ) VALUES (?, ?, 'INCOME', ?, ?, '입금', 100000, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                sourceAccountId,
                externalTransactionKey,
                amount,
                NOW.plusHours(1),
                NOW,
                NOW
        );
        jdbcTemplate.update("""
                INSERT INTO settlement_deposits (
                    id, settlement_id, share_id, source_account_id,
                    account_transaction_id, matched_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                settlementId,
                shareId,
                sourceAccountId,
                transactionId,
                NOW.plusHours(1)
        );
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "settlement-deposit-" + prefix + "-" + unique,
                prefix,
                prefix + "_" + unique.replace("-", "").substring(0, 12),
                prefix + "-" + unique.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private void insertAwaitingSettlement(UUID settlementId, Long accountId) {
        UUID participantId = UUID.randomUUID();
        UUID shareId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO settlements (
                    id, creator_id, title, total_amount, status, created_at, updated_at
                ) VALUES (?, ?, '회식 정산', 10000, 'AWAITING_DEPOSIT', ?, ?)
                """, settlementId, creatorId, NOW, NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_participants (
                    id, settlement_id, source, linked_user_id, phone_hash, display_name,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, 'REGISTERED_USER', ?, NULL, '민선영', 0, ?, ?)
                """, participantId, settlementId, payerId, NOW, NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization, account_number_ciphertext,
                    account_holder, created_at
                ) VALUES (?, ?, '0020', ?, '생성자', ?)
                """,
                settlementId,
                accountId,
                cipher.encryptSettlementAccountNumber(settlementId, ACCOUNT_NUMBER),
                NOW
        );
        jdbcTemplate.update("""
                INSERT INTO settlement_shares (
                    id, settlement_id, payer_participant_id, amount, created_at
                ) VALUES (?, ?, ?, 6000, ?)
                """, shareId, settlementId, participantId, NOW);
    }
}
