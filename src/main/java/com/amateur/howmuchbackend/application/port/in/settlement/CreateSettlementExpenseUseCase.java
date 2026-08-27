package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;

import java.util.UUID;

public interface CreateSettlementExpenseUseCase {
    SettlementExpenseMutationResult create(
            UUID settlementId,
            CreateSettlementExpenseCommand command
    );
}
