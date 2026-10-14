package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementTransferIntentResult(UUID settlementId,
        SettlementDepositReconciliationStatus status, LocalDateTime scheduledAt) {
    public static SettlementTransferIntentResult from(SettlementDepositReconciliationJob job) {
        return new SettlementTransferIntentResult(job.settlementId(), job.status(), job.nextAttemptAt());
    }
}
