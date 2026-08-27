package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;

import java.util.UUID;

public interface UpdateSettlementExpenseUseCase {
    SettlementExpenseMutationResult update(
            UUID settlementId,
            UUID expenseId,
            UpdateSettlementExpenseCommand command
    );
}
