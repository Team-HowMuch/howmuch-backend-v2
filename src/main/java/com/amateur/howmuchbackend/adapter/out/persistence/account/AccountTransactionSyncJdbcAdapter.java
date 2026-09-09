package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.adapter.out.persistence.common.PostgresLockTimeoutConfigurer;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandOutboxPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncJobStorePort;
import com.amateur.howmuchbackend.application.port.out.account.FinanceSyncReferenceStorePort;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncJob;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountTransactionSyncJdbcAdapter implements
        FinanceSyncReferenceStorePort,
        AccountTransactionSyncJobStorePort,
        AccountTransactionSyncCommandOutboxPort {
    private final JdbcClient jdbc;
    private final PostgresLockTimeoutConfigurer lockTimeoutConfigurer;

    @Override
    @Transactional
    public FinanceSyncReferences prepare(Long externalConnectionId, List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            throw new IllegalArgumentException("등록할 활성 금융 계좌가 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO finance_connection_refs (
                            external_connection_id, connection_ref, connection_version,
                            registered_at, created_at, updated_at
                        )
                        SELECT id, :connectionRef, 0, NULL, :now, :now
                          FROM external_connections
                         WHERE id = :externalConnectionId
                           AND active = TRUE
                        ON CONFLICT (external_connection_id) DO NOTHING
                        """)
                .param("connectionRef", UUID.randomUUID())
                .param("externalConnectionId", externalConnectionId)
                .param("now", now)
                .update();
        UUID connectionRef = jdbc.sql("""
                        SELECT connection_ref
                          FROM finance_connection_refs
                         WHERE external_connection_id = :externalConnectionId
                        """)
                .param("externalConnectionId", externalConnectionId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("활성 금융 연결 참조를 준비할 수 없습니다."));

        for (Long accountId : accountIds) {
            jdbc.sql("""
                            INSERT INTO finance_account_refs (
                                account_id, connection_ref, account_ref, sync_version,
                                created_at, updated_at
                            )
                            SELECT account.id, :connectionRef, :accountRef, 0, :now, :now
                              FROM accounts AS account
                             WHERE account.id = :accountId
                               AND account.external_connection_id = :externalConnectionId
                               AND account.active = TRUE
                            ON CONFLICT (account_id) DO NOTHING
                            """)
                    .param("connectionRef", connectionRef)
                    .param("accountRef", UUID.randomUUID())
                    .param("now", now)
                    .param("accountId", accountId)
                    .param("externalConnectionId", externalConnectionId)
                    .update();
        }
        Map<Long, UUID> accountRefs = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT account_id, account_ref
                          FROM finance_account_refs
                         WHERE connection_ref = :connectionRef
                           AND account_id IN (:accountIds)
                         ORDER BY account_id
                        """)
                .param("connectionRef", connectionRef)
                .param("accountIds", accountIds)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getLong("account_id"),
                        resultSet.getObject("account_ref", UUID.class)
                ))
                .list()
                .forEach(entry -> accountRefs.put(entry.getKey(), entry.getValue()));
        if (accountRefs.size() != accountIds.size()) {
            throw new IllegalStateException("활성 금융 계좌 참조를 모두 준비할 수 없습니다.");
        }
        return new FinanceSyncReferences(connectionRef, Map.copyOf(accountRefs));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountTransactionSyncJob> findByIdempotencyKey(
            Long userId,
            UUID idempotencyKey
    ) {
        return findJob("""
                SELECT *
                  FROM account_transaction_sync_jobs
                 WHERE user_id = :userId
                   AND idempotency_key = :idempotencyKey
                """, Map.of("userId", userId, "idempotencyKey", idempotencyKey));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountTransactionSyncJob> findByIdAndUserId(UUID syncJobId, Long userId) {
        return findJob("""
                SELECT *
                  FROM account_transaction_sync_jobs
                 WHERE id = :syncJobId
                   AND user_id = :userId
                """, Map.of("syncJobId", syncJobId, "userId", userId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveJob(Long accountId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM account_transaction_sync_jobs
                             WHERE account_id = :accountId
                               AND status IN ('QUEUED', 'COLLECTING', 'APPLYING')
                        )
                        """)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional
    public EnqueueResult enqueue(EnqueueCommand command) {
        lockTimeoutConfigurer.apply();
        if (command.connectionVersion() < 1) {
            return new EnqueueResult(EnqueueResult.Outcome.STALE_REGISTRATION, null);
        }
        boolean ownerExists = jdbc.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .param("userId", command.userId())
                .query(Long.class)
                .optional()
                .isPresent();
        if (!ownerExists) {
            return new EnqueueResult(EnqueueResult.Outcome.ACCOUNT_UNAVAILABLE, null);
        }
        var existing = findByIdempotencyKey(command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return new EnqueueResult(EnqueueResult.Outcome.EXISTING, existing.get());
        }

        boolean accountAvailable = jdbc.sql("""
                        SELECT account.id
                          FROM accounts AS account
                          JOIN finance_account_refs AS account_ref
                            ON account_ref.account_id = account.id
                          JOIN finance_connection_refs AS connection_ref
                            ON connection_ref.connection_ref = account_ref.connection_ref
                         WHERE account.id = :accountId
                           AND account.user_id = :userId
                           AND account.external_connection_id = :externalConnectionId
                           AND account.active = TRUE
                           AND account_ref.account_ref = :accountRef
                           AND connection_ref.connection_ref = :connectionRef
                         FOR UPDATE
                        """)
                .param("accountId", command.accountId())
                .param("userId", command.userId())
                .param("externalConnectionId", command.externalConnectionId())
                .param("accountRef", command.accountRef())
                .param("connectionRef", command.connectionRef())
                .query(Long.class)
                .optional()
                .isPresent();
        if (!accountAvailable) {
            return new EnqueueResult(EnqueueResult.Outcome.ACCOUNT_UNAVAILABLE, null);
        }
        if (hasActiveJob(command.accountId())) {
            return new EnqueueResult(EnqueueResult.Outcome.ACTIVE_ACCOUNT_CONFLICT, null);
        }

        Long storedConnectionVersion = jdbc.sql("""
                        SELECT connection_version
                          FROM finance_connection_refs
                         WHERE external_connection_id = :externalConnectionId
                           AND connection_ref = :connectionRef
                         FOR UPDATE
                        """)
                .param("externalConnectionId", command.externalConnectionId())
                .param("connectionRef", command.connectionRef())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (storedConnectionVersion == null || storedConnectionVersion > command.connectionVersion()) {
            return new EnqueueResult(EnqueueResult.Outcome.STALE_REGISTRATION, null);
        }
        jdbc.sql("""
                        UPDATE finance_connection_refs
                           SET connection_version = :connectionVersion,
                               registered_at = :requestedAt,
                               updated_at = :requestedAt
                         WHERE external_connection_id = :externalConnectionId
                           AND connection_ref = :connectionRef
                        """)
                .param("connectionVersion", command.connectionVersion())
                .param("requestedAt", command.requestedAt())
                .param("externalConnectionId", command.externalConnectionId())
                .param("connectionRef", command.connectionRef())
                .update();
        long syncVersion = jdbc.sql("""
                        UPDATE finance_account_refs
                           SET sync_version = sync_version + 1,
                               updated_at = :requestedAt
                         WHERE account_id = :accountId
                           AND account_ref = :accountRef
                        RETURNING sync_version
                        """)
                .param("requestedAt", command.requestedAt())
                .param("accountId", command.accountId())
                .param("accountRef", command.accountRef())
                .query(Long.class)
                .single();

        UUID syncJobId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO account_transaction_sync_jobs (
                            id, user_id, account_id, connection_version, sync_version,
                            idempotency_key, request_hash, from_date, to_date, status,
                            created_at, updated_at
                        ) VALUES (
                            :id, :userId, :accountId, :connectionVersion, :syncVersion,
                            :idempotencyKey, :requestHash, :fromDate, :toDate, 'QUEUED',
                            :requestedAt, :requestedAt
                        )
                        """)
                .param("id", syncJobId)
                .param("userId", command.userId())
                .param("accountId", command.accountId())
                .param("connectionVersion", command.connectionVersion())
                .param("syncVersion", syncVersion)
                .param("idempotencyKey", command.idempotencyKey())
                .param("requestHash", command.requestHash())
                .param("fromDate", command.fromDate())
                .param("toDate", command.toDate())
                .param("requestedAt", command.requestedAt())
                .update();
        jdbc.sql("""
                        INSERT INTO account_transaction_sync_outbox (
                            id, sync_job_id, status, attempt_count, next_attempt_at,
                            created_at, updated_at
                        ) VALUES (
                            :id, :syncJobId, 'PENDING', 0, :requestedAt,
                            :requestedAt, :requestedAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("syncJobId", syncJobId)
                .param("requestedAt", command.requestedAt())
                .update();
        return new EnqueueResult(
                EnqueueResult.Outcome.CREATED,
                findByIdAndUserId(syncJobId, command.userId()).orElseThrow()
        );
    }

    @Override
    @Transactional
    public Optional<PendingCommand> claimNextReady(LocalDateTime now) {
        lockTimeoutConfigurer.apply();
        Optional<UUID> claimed = jdbc.sql("""
                        WITH candidate AS (
                            SELECT id
                              FROM account_transaction_sync_outbox
                             WHERE status IN ('PENDING', 'RETRY')
                               AND next_attempt_at <= :now
                             ORDER BY next_attempt_at, created_at, id
                             FOR UPDATE SKIP LOCKED
                             LIMIT 1
                        )
                        UPDATE account_transaction_sync_outbox AS outbox
                           SET status = 'PROCESSING',
                               attempt_count = outbox.attempt_count + 1,
                               next_attempt_at = NULL,
                               processing_started_at = :now,
                               updated_at = :now
                          FROM candidate
                         WHERE outbox.id = candidate.id
                        RETURNING outbox.id
                        """)
                .param("now", now)
                .query(UUID.class)
                .optional();
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT outbox.id AS outbox_id,
                               outbox.attempt_count,
                               outbox.created_at AS occurred_at,
                               job.id AS sync_job_id,
                               account_ref.account_ref,
                               job.sync_version,
                               job.connection_version,
                               connection_ref.connection_ref,
                               job.from_date,
                               job.to_date,
                               job.created_at + INTERVAL '10 minutes' AS deadline_at
                          FROM account_transaction_sync_outbox AS outbox
                          JOIN account_transaction_sync_jobs AS job
                            ON job.id = outbox.sync_job_id
                          JOIN finance_account_refs AS account_ref
                            ON account_ref.account_id = job.account_id
                          JOIN finance_connection_refs AS connection_ref
                            ON connection_ref.connection_ref = account_ref.connection_ref
                         WHERE outbox.id = :outboxId
                        """)
                .param("outboxId", claimed.get())
                .query((resultSet, rowNumber) -> new PendingCommand(
                        resultSet.getObject("outbox_id", UUID.class),
                        resultSet.getInt("attempt_count"),
                        timestamp(resultSet, "occurred_at"),
                        resultSet.getObject("sync_job_id", UUID.class),
                        resultSet.getObject("account_ref", UUID.class),
                        resultSet.getLong("sync_version"),
                        resultSet.getLong("connection_version"),
                        resultSet.getObject("connection_ref", UUID.class),
                        resultSet.getObject("from_date", LocalDate.class),
                        resultSet.getObject("to_date", LocalDate.class),
                        timestamp(resultSet, "deadline_at")
                ))
                .optional();
    }

    @Override
    @Transactional
    public boolean markPublished(UUID outboxId, int attemptCount, LocalDateTime publishedAt) {
        return jdbc.sql("""
                        UPDATE account_transaction_sync_outbox
                           SET status = 'SUCCEEDED',
                               processing_started_at = NULL,
                               completed_at = :publishedAt,
                               updated_at = :publishedAt
                         WHERE id = :outboxId
                           AND status = 'PROCESSING'
                           AND attempt_count = :attemptCount
                        """)
                .param("publishedAt", publishedAt)
                .param("outboxId", outboxId)
                .param("attemptCount", attemptCount)
                .update() == 1;
    }

    private Optional<AccountTransactionSyncJob> findJob(String sql, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (var parameter : parameters.entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query((resultSet, rowNumber) -> job(resultSet)).optional();
    }

    private AccountTransactionSyncJob job(ResultSet resultSet) throws SQLException {
        return new AccountTransactionSyncJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("user_id"),
                resultSet.getLong("account_id"),
                resultSet.getObject("idempotency_key", UUID.class),
                resultSet.getString("request_hash"),
                resultSet.getObject("from_date", LocalDate.class),
                resultSet.getObject("to_date", LocalDate.class),
                AccountTransactionSyncStatus.valueOf(resultSet.getString("status")),
                timestamp(resultSet, "created_at"),
                timestamp(resultSet, "completed_at"),
                timestamp(resultSet, "last_synced_at"),
                resultSet.getString("failure_code"),
                resultSet.getString("safe_message")
        );
    }

    private LocalDateTime timestamp(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
