package com.amateur.howmuchbackend.application.port.in.settlement.command;

public record SettlementContactCommand(
        String contactId,
        String displayName,
        String phoneNumber
) {
}
