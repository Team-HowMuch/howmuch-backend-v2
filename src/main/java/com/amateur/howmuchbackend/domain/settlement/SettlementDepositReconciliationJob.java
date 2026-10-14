package com.amateur.howmuchbackend.domain.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementDepositReconciliationJob(
        UUID settlementId, long generation, SettlementDepositReconciliationStatus status,
        int attemptCount, int maxAttempts, LocalDateTime nextAttemptAt, UUID leaseToken,
        LocalDateTime leaseUntil, LocalDateTime firstSignaledAt, LocalDateTime expiresAt
) {}
