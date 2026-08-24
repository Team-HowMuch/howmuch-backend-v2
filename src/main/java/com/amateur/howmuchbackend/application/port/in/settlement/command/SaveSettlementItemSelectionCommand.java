package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;

public record SaveSettlementItemSelectionCommand(
        SettlementItemAllocationFraction fraction
) {
}
