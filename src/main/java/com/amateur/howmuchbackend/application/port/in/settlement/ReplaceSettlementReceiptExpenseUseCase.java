package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import java.util.UUID;

public interface ReplaceSettlementReceiptExpenseUseCase {
    SettlementReceiptExpenseMutationResult replace(
            UUID settlementId, UUID expenseId, ReplaceSettlementReceiptExpenseCommand command
    );
}
