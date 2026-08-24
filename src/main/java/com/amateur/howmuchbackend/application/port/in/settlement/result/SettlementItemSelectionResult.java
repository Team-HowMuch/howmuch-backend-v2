package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;

import java.util.UUID;

public record SettlementItemSelectionResult(
        UUID participantId,
        String participantDisplayName,
        SettlementItemAllocationFraction fraction,
        long amount
) {
}
