package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.util.List;

public record MatchSettlementContactsCommand(
        List<SettlementContactCommand> contacts
) {
}
