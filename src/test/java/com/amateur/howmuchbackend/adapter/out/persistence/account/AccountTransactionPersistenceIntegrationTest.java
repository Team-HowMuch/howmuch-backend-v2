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
}
