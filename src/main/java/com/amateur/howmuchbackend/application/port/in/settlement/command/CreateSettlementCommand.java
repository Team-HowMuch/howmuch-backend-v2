package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record CreateSettlementCommand(
        String title,
        long totalAmount
) {
}
