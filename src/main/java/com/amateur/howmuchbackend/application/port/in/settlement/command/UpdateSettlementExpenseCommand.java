package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record UpdateSettlementExpenseCommand(
        String description,
        Long amount
) {
}
