package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record UpdateSettlementDraftCommand(
        String title,
        Long totalAmount
) {
}
