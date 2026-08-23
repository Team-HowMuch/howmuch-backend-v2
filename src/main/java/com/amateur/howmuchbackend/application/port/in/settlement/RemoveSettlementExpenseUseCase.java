package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseTotalResult;

import java.util.UUID;

public interface RemoveSettlementExpenseUseCase {
    SettlementExpenseTotalResult remove(UUID settlementId, UUID expenseId);
}
