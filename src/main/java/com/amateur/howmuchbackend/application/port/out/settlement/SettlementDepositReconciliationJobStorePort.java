package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SettlementDepositReconciliationJobStorePort {
    Optional<SettlementDepositReconciliationJob> schedule(UUID settlementId, LocalDateTime now,
            LocalDateTime nextAttemptAt, LocalDateTime expiresAt, LocalDateTime replaceBefore);
    Optional<SettlementDepositReconciliationJob> claimDue(LocalDateTime now, LocalDateTime leaseUntil);
    boolean renew(SettlementDepositReconciliationJob claim, LocalDateTime now, LocalDateTime leaseUntil);
    boolean lockCurrentClaim(SettlementDepositReconciliationJob claim, LocalDateTime now);
    boolean finish(SettlementDepositReconciliationJob claim, SettlementDepositReconciliationStatus status,
            LocalDateTime nextAttemptAt, String failureCode, LocalDateTime now);
}
