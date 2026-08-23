package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;

import java.util.UUID;

public interface ImportSettlementReceiptExpenseUseCase {
    SettlementReceiptExpenseMutationResult importExpense(
            UUID settlementId,
            ImportSettlementReceiptExpenseCommand command
    );
}
