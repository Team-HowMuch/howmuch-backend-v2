package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementPaymentExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;

import java.util.UUID;

public interface ImportSettlementPaymentExpenseUseCase {
    SettlementExpenseMutationResult importExpense(
            UUID settlementId,
            ImportSettlementPaymentExpenseCommand command
    );
}
