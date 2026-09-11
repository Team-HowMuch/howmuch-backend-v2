package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.external.ExternalConnectionPersistenceAdapter;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=aG93bXVjaC1jb2RlZi1tb2NrLWtleS0zMi1ieXRlcyE="
})
@DisplayName("계좌 거래와 월 요약 영속 통합")
class AccountTransactionPersistenceIntegrationTest {
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Autowired
    private AccountTransactionPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExternalConnectionPersistenceAdapter connectionAdapter;

    @Autowired
    private CodefSecretCipher cipher;

    @Autowired
    private CodefConfigurationFingerprint configurationFingerprint;

    private Long userId;
    private Long connectionId;
    private Long accountId;
    private Long foreignUserId;
    private Long otherAccountRecordId;
    private Long foreignAccountRecordId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 0, 0);
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, '계좌 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "account-transaction-" + unique,
                "acct_" + unique.replace("-", "").substring(0, 12),
                "test-" + unique.substring(0, 12),
                now, now, now
        );
        connectionId = jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint, active,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                cipher.encryptConnectedId(userId, "CID-TEST"),
                configurationFingerprint.current(),
                now,
                now
        );
        accountId = jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number, balance,
                            currency, active, product_code, is_default, display_order, created_at, updated_at
                        ) VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '생활비 통장',
                                  '****6789', 1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                connectionId,
                "a".repeat(64),
                cipher.encryptAccountKey(userId, connectionId, "1002123456789"),
                now,
                now
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                DELETE FROM settlement_expenses
                 WHERE settlement_id IN (SELECT id FROM settlements WHERE creator_id = ?)
                """, userId);
        jdbcTemplate.update("DELETE FROM settlements WHERE creator_id = ?", userId);
        if (foreignUserId != null) {
            jdbcTemplate.update("""
                    DELETE FROM settlement_expenses
                     WHERE settlement_id IN (SELECT id FROM settlements WHERE creator_id = ?)
                    """, foreignUserId);
            jdbcTemplate.update("DELETE FROM settlements WHERE creator_id = ?", foreignUserId);
            if (foreignAccountRecordId != null) {
                jdbcTemplate.update(
                        "DELETE FROM account_records WHERE id = ?",
                        foreignAccountRecordId
                );
            }
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", foreignUserId);
        }
        if (otherAccountRecordId != null) {
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", otherAccountRecordId);
        }
        jdbcTemplate.update("DELETE FROM account_monthly_summaries WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("중복 거래를 한 번만 저장하고 출금만 월 소비로 집계한다")
    void storesTransactionsAndSummaryWithoutDuplicates() {
        Transaction expense = transaction(
                "a".repeat(64), AccountTransactionType.EXPENSE, "12500", LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        Transaction income = transaction(
                "b".repeat(64), AccountTransactionType.INCOME, "50000", LocalDateTime.of(2026, 8, 21, 1, 15)
        );
        Transaction monthBoundary = transaction(
                "c".repeat(64), AccountTransactionType.EXPENSE, "2500", LocalDateTime.of(2026, 7, 31, 15, 30)
        );

        adapter.saveAllIgnoringDuplicates(List.of(expense, expense, income, monthBoundary));
        var calculated = adapter.calculateSummary(accountId, AUGUST);
        adapter.saveSummaryIfAbsent(calculated);
        adapter.saveSummaryIfAbsent(calculated);

        assertThat(adapter.findByAccountIdAndYearMonth(accountId, AUGUST)).hasSize(3);
        assertThat(calculated.monthlyExpenseAmount()).isEqualByComparingTo("15000");
        assertThat(calculated.expenseCount()).isEqualTo(2);
        assertThat(calculated.lastUsedAt()).isEqualTo(LocalDateTime.of(2026, 8, 21, 1, 15));
        assertThat(adapter.findSummary(accountId, AUGUST)).hasValueSatisfying(summary -> {
            assertThat(summary.monthlyExpenseAmount()).isEqualByComparingTo("15000");
            assertThat(summary.expenseCount()).isEqualTo(2);
        });

        YearMonth emptyMonth = YearMonth.of(2026, 7);
        var empty = adapter.calculateSummary(accountId, emptyMonth);
        adapter.saveSummaryIfAbsent(empty);
        assertThat(adapter.findSummary(accountId, emptyMonth)).hasValueSatisfying(summary -> {
            assertThat(summary.monthlyExpenseAmount()).isZero();
            assertThat(summary.expenseCount()).isZero();
            assertThat(summary.lastUsedAt()).isNull();
        });
    }

    @Test
    @DisplayName("거래 Key에 연결된 여러 정산을 원본 계좌 범위에서 조회한다")
    void loadsLinkedSettlementsForTransactions() {
        String transactionKey = "g".repeat(64);
        UUID olderSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001001");
        UUID newerSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001002");
        UUID foreignSettlementId = UUID.fromString("13000000-0000-4000-8000-000000001003");
        UUID otherAccountSettlementId = UUID.fromString(
                "13000000-0000-4000-8000-000000001004"
        );
        LocalDateTime older = LocalDateTime.of(2026, 8, 20, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 8, 21, 0, 0);
        insertSettlement(olderSettlementId, "점심 정산", "AWAITING_DEPOSIT", older);
        insertSettlement(newerSettlementId, "여행 정산", "COMPLETED", newer);
        otherAccountRecordId = insertAccountRecord(userId, "다른 계좌");
        insertSettlement(
                otherAccountSettlementId,
                "다른 계좌 정산",
                "AWAITING_DEPOSIT",
                newer.plusHours(1)
        );
        foreignUserId = insertUser("foreign-account-transaction");
        foreignAccountRecordId = insertAccountRecord(foreignUserId, "다른 사용자 계좌");
        insertSettlement(
                foreignSettlementId,
                foreignUserId,
                "다른 사용자 정산",
                "AWAITING_DEPOSIT",
                newer.plusDays(1)
        );
        insertPaymentExpense(olderSettlementId, accountId, transactionKey, older);
        insertPaymentExpense(newerSettlementId, accountId, transactionKey, newer);
        insertPaymentExpense(
                otherAccountSettlementId,
                otherAccountRecordId,
                transactionKey,
                newer.plusHours(1)
        );
        insertPaymentExpense(
                foreignSettlementId,
                foreignAccountRecordId,
                transactionKey,
                newer.plusDays(1)
        );

        var links = adapter.findLinkedSettlements(
                accountId,
                List.of(transactionKey, "h".repeat(64))
        );

        assertThat(links)
                .extracting(
                        link -> link.settlementId(),
                        link -> link.title(),
                        link -> link.status().name()
                )
                .containsExactly(
                        tuple(newerSettlementId, "여행 정산", "COMPLETED"),
                        tuple(olderSettlementId, "점심 정산", "AWAITING_DEPOSIT")
                );
        assertThat(adapter.findLinkedSettlements(accountId, List.of())).isEmpty();
    }

    @Test
    @DisplayName("새 거래가 실제 저장된 월만 기존 월 요약을 무효화한다")
    void invalidatesOnlyChangedMonthlySummary() {
        Transaction first = transaction(
                "e".repeat(64), AccountTransactionType.EXPENSE, "1000",
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        Transaction second = transaction(
                "f".repeat(64), AccountTransactionType.EXPENSE, "2000",
                LocalDateTime.of(2026, 8, 21, 0, 15)
        );
        adapter.saveAllIgnoringDuplicates(List.of(first));
        adapter.saveSummaryIfAbsent(adapter.calculateSummary(accountId, AUGUST));

        adapter.saveAllIgnoringDuplicates(List.of(first));
        assertThat(adapter.findSummary(accountId, AUGUST)).isPresent();

        adapter.saveAllIgnoringDuplicates(List.of(second));
        assertThat(adapter.findSummary(accountId, AUGUST)).isEmpty();
        assertThat(adapter.calculateSummary(accountId, AUGUST).monthlyExpenseAmount())
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("활성 Account 삭제 뒤에도 Account Record FK로 거래와 월 요약을 조회한다")
    void loadsHistoryAfterActiveAccountDeletion() {
        Transaction expense = transaction(
                "d".repeat(64), AccountTransactionType.EXPENSE, "12500",
                LocalDateTime.of(2026, 8, 20, 0, 15)
        );
        adapter.saveAllIgnoringDuplicates(List.of(expense));
        adapter.saveSummaryIfAbsent(adapter.calculateSummary(accountId, AUGUST));

        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);

        assertThat(adapter.findByAccountIdAndYearMonth(accountId, AUGUST)).singleElement()
                .satisfies(transaction -> assertThat(transaction.accountId()).isEqualTo(accountId));
        assertThat(adapter.findSummary(accountId, AUGUST)).hasValueSatisfying(summary ->
                assertThat(summary.accountId()).isEqualTo(accountId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_records WHERE id = ? AND deleted_at IS NOT NULL",
                Long.class,
                accountId
        )).isOne();
        assertThat(jdbcTemplate.queryForList("""
                        SELECT confrelid::regclass::text
                          FROM pg_constraint
                         WHERE conname IN (
                             'fk_account_transactions_account_record',
                             'fk_account_monthly_summaries_account_record'
                         )
                         ORDER BY conname
                        """, String.class))
                .containsExactly("account_records", "account_records");
    }

    @Test
    @DisplayName("잠금 조회가 쓰기 가능한 트랜잭션에서 연결 ID를 복호화한다")
    void loadsConnectionWithPessimisticLock() {
        assertThat(connectionAdapter.findActiveByIdAndUserId(connectionId, userId))
                .hasValueSatisfying(connection -> assertThat(connection.connectedId()).isEqualTo("CID-TEST"));
    }

    private Transaction transaction(
            String key,
            AccountTransactionType type,
            String amount,
            LocalDateTime transactedAt
    ) {
        return new Transaction(
                null, accountId, key, type, new BigDecimal(amount), transactedAt, "테스트 거래",
                new BigDecimal("1000000")
        );
    }

    private void insertSettlement(
            UUID settlementId,
            String title,
            String status,
            LocalDateTime now
    ) {
        insertSettlement(settlementId, userId, title, status, now);
    }

    private void insertSettlement(
            UUID settlementId,
            Long creatorId,
            String title,
            String status,
            LocalDateTime now
    ) {
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type, status,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 10000, 'DUTCH_PAY', ?, ?, ?)
                """,
                settlementId,
                creatorId,
                title,
                status,
                now,
                now
        );
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 0, 0);
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, '다른 사용자', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                prefix + "-" + unique,
                "foreign_" + unique.replace("-", "").substring(0, 12),
                "foreign-" + unique.substring(0, 12),
                now,
                now,
                now
        );
    }

    private Long insertAccountRecord(Long ownerId, String name) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('accounts', 'id'))",
                Long.class
        );
        jdbcTemplate.update("""
                        INSERT INTO account_records (
                            id, user_id, organization, type, name, masked_number, currency,
                            linked_at
                        ) VALUES (?, ?, '0020', 'DEPOSIT_TRUST', ?, '****0000', 'KRW', ?)
                        """,
                id,
                ownerId,
                name,
                LocalDateTime.of(2026, 8, 23, 0, 0)
        );
        return id;
    }

    private void insertPaymentExpense(
            UUID settlementId,
            Long sourceAccountId,
            String transactionKey,
            LocalDateTime now
    ) {
        jdbcTemplate.update("""
                        INSERT INTO settlement_expenses (
                            id, settlement_id, source, source_reference, source_account_id,
                            description, amount, display_order, created_at, updated_at
                        ) VALUES (?, ?, 'PAYMENT_HISTORY', ?, ?, '테스트 거래', 10000, 0, ?, ?)
                        """,
                UUID.randomUUID(),
                settlementId,
                transactionKey,
                sourceAccountId,
                now,
                now
        );
    }
}
