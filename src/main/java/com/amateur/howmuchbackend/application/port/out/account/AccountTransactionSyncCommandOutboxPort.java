package com.amateur.howmuchbackend.application.port.out.account;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AccountTransactionSyncCommandOutboxPort {
    Optional<PendingCommand> claimNextReady(LocalDateTime now);

    boolean markPublished(UUID outboxId, int attemptCount, LocalDateTime publishedAt);

    record PendingCommand(
            UUID outboxId,
            int attemptCount,
            LocalDateTime occurredAt,
            UUID syncJobId,
            UUID accountRef,
            long syncVersion,
            long connectionVersion,
            UUID connectionRef,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDateTime deadlineAt
    ) {
    }
}
