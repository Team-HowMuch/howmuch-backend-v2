package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseListResult;

import java.util.UUID;

public interface GetSettlementExpensesUseCase {
    SettlementExpenseListResult get(UUID settlementId);
}
