package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncJob;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AccountTransactionSyncJobStorePort {
    Optional<AccountTransactionSyncJob> findByIdempotencyKey(Long userId, UUID idempotencyKey);

    Optional<AccountTransactionSyncJob> findByIdAndUserId(UUID syncJobId, Long userId);

    boolean hasActiveJob(Long accountId);

    EnqueueResult enqueue(EnqueueCommand command);

    record EnqueueCommand(
            Long userId,
            Long accountId,
            Long externalConnectionId,
            UUID connectionRef,
            UUID accountRef,
            long connectionVersion,
            UUID idempotencyKey,
            String requestHash,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDateTime requestedAt
    ) {
    }

    record EnqueueResult(Outcome outcome, AccountTransactionSyncJob job) {
        public enum Outcome {
            CREATED,
            EXISTING,
            ACTIVE_ACCOUNT_CONFLICT,
            ACCOUNT_UNAVAILABLE,
            STALE_REGISTRATION
        }
    }
}
