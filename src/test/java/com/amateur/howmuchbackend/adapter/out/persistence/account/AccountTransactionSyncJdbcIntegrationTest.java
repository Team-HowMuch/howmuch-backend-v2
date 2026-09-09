package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncJobStorePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("계좌 거래 동기화 job과 Outbox 영속 통합")
class AccountTransactionSyncJdbcIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 3, 0);

    @Autowired private AccountTransactionSyncJdbcAdapter adapter;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long otherUserId;
    private Long connectionId;
    private Long accountId;
    private Long secondAccountId;
    private UUID connectionRef;
    private UUID accountRef;

    @BeforeEach
    void setUp() {
        userId = insertUser("sync-owner");
        otherUserId = insertUser("sync-foreign");
        connectionId = jdbc.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint,
                            active, created_at, updated_at
                        ) VALUES (?, 'ciphertext', ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class, userId, "f".repeat(64), NOW, NOW);
        accountId = insertAccount("a".repeat(64), "생활비 계좌", 0);
        secondAccountId = insertAccount("b".repeat(64), "저축 계좌", 1);
        var references = adapter.prepare(connectionId, List.of(accountId, secondAccountId));
        connectionRef = references.connectionRef();
        accountRef = references.accountRef(accountId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM account_transaction_sync_outbox WHERE sync_job_id IN (SELECT id FROM account_transaction_sync_jobs WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM account_transaction_sync_jobs WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM finance_account_refs WHERE account_id IN (?, ?)", accountId, secondAccountId);
        jdbc.update("DELETE FROM finance_connection_refs WHERE external_connection_id = ?", connectionId);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?)", accountId, secondAccountId);
        jdbc.update("DELETE FROM account_records WHERE id IN (?, ?)", accountId, secondAccountId);
        jdbc.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
    }

    @Test
    @DisplayName("참조를 재사용하고 멱등 job과 Outbox를 원자 생성한다")
    void createsIdempotentJobAndOutbox() {
        var repeatedReferences = adapter.prepare(connectionId, List.of(accountId, secondAccountId));
        assertThat(repeatedReferences.connectionRef()).isEqualTo(connectionRef);
        assertThat(repeatedReferences.accountRef(accountId)).isEqualTo(accountRef);

        UUID idempotencyKey = UUID.randomUUID();
        var first = adapter.enqueue(command(idempotencyKey, 1, NOW));
        assertThat(adapter.hasActiveJob(accountId)).isTrue();
        var duplicate = adapter.enqueue(command(idempotencyKey, 1, NOW.plusSeconds(1)));
        var activeConflict = adapter.enqueue(command(UUID.randomUUID(), 1, NOW.plusSeconds(2)));

        assertThat(first.outcome()).isEqualTo(AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.CREATED);
        assertThat(duplicate.outcome()).isEqualTo(AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.EXISTING);
        assertThat(duplicate.job().id()).isEqualTo(first.job().id());
        assertThat(activeConflict.outcome())
                .isEqualTo(AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.ACTIVE_ACCOUNT_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_transaction_sync_jobs WHERE account_id = ?",
                Integer.class,
                accountId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_transaction_sync_outbox WHERE sync_job_id = ?",
                Integer.class,
                first.job().id()
        )).isOne();

        var pending = adapter.claimNextReady(NOW.plusSeconds(3)).orElseThrow();
        assertThat(pending.syncJobId()).isEqualTo(first.job().id());
        assertThat(pending.accountRef()).isEqualTo(accountRef);
        assertThat(pending.syncVersion()).isEqualTo(1);
        assertThat(pending.connectionVersion()).isEqualTo(1);
        assertThat(pending.deadlineAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(adapter.markPublished(pending.outboxId(), pending.attemptCount(), NOW.plusSeconds(4)))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM account_transaction_sync_outbox WHERE id = ?",
                String.class,
                pending.outboxId()
        )).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("terminal 작업 이후 계좌별 syncVersion을 증가시키고 사용자 소유권을 적용한다")
    void incrementsVersionAndScopesOwnership() {
        var first = adapter.enqueue(command(UUID.randomUUID(), 1, NOW));
        jdbc.update("""
                        UPDATE account_transaction_sync_jobs
                           SET status = 'CANCELLED',
                               completed_at = ?,
                               failure_code = 'CANCELLED',
                               safe_message = '테스트 종료',
                               updated_at = ?
                         WHERE id = ?
                        """,
                NOW.plusSeconds(1), NOW.plusSeconds(1), first.job().id());

        var second = adapter.enqueue(command(UUID.randomUUID(), 1, NOW.plusSeconds(2)));

        assertThat(second.outcome()).isEqualTo(AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.CREATED);
        assertThat(jdbc.queryForList(
                "SELECT sync_version FROM account_transaction_sync_jobs WHERE account_id = ? ORDER BY sync_version",
                Long.class,
                accountId
        )).containsExactly(1L, 2L);
        assertThat(adapter.findByIdAndUserId(second.job().id(), userId)).isPresent();
        assertThat(adapter.findByIdAndUserId(second.job().id(), otherUserId)).isEmpty();
    }

    private AccountTransactionSyncJobStorePort.EnqueueCommand command(
            UUID idempotencyKey,
            long connectionVersion,
            LocalDateTime requestedAt
    ) {
        return new AccountTransactionSyncJobStorePort.EnqueueCommand(
                userId,
                accountId,
                connectionId,
                connectionRef,
                accountRef,
                connectionVersion,
                idempotencyKey,
                "c".repeat(64),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                requestedAt
        );
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, '동기화 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                prefix + "-" + unique,
                "sync_" + unique.replace("-", "").substring(0, 12),
                "test-" + unique.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private Long insertAccount(String candidateKey, String name, int displayOrder) {
        return jdbc.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number, balance,
                            currency, active, product_code, is_default, display_order, created_at, updated_at
                        ) VALUES (?, ?, '0020', ?, 'ciphertext', 'DEPOSIT_TRUST', ?, '****0001',
                                  0, 'KRW', TRUE, '11', FALSE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                connectionId,
                candidateKey,
                name,
                displayOrder,
                NOW,
                NOW
        );
    }
}
