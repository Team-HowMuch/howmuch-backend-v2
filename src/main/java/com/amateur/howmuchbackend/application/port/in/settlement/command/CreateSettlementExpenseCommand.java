package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record CreateSettlementExpenseCommand(
        String description,
        long amount
) {
}
