package com.amateur.howmuchbackend.application.port.in.settlement.command;

import java.util.List;
import java.util.UUID;

public record ReplaceSettlementDutchPayAllocationsCommand(
        List<Allocation> allocations
) {
    public record Allocation(
            UUID participantId,
            long amount
    ) {
    }
}
