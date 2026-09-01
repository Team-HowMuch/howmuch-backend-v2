package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;

import java.util.UUID;

public interface CreateSettlementReceiptExpenseUseCase {
    SettlementReceiptExpenseMutationResult create(
            UUID settlementId,
            CreateSettlementReceiptExpenseCommand command
    );
}
