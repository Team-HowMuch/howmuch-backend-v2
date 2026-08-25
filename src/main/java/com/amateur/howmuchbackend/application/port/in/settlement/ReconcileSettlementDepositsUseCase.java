package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;

import java.util.UUID;

public interface ReconcileSettlementDepositsUseCase {
    SettlementDepositSummaryResult reconcile(UUID settlementId);
}
