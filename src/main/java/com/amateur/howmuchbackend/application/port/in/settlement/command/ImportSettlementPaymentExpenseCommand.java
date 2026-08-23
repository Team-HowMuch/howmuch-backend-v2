package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record ImportSettlementPaymentExpenseCommand(
        Long accountId,
        Long transactionId
) {
}
