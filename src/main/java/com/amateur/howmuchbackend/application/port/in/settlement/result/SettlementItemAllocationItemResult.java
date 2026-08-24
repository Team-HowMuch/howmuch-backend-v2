package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;
import java.util.UUID;

public record SettlementItemAllocationItemResult(
        UUID itemId,
        String name,
        int quantity,
        long amount,
        int allocatedUnits,
        int totalUnits,
        List<SettlementItemSelectionResult> selections
) {
    public SettlementItemAllocationItemResult {
        selections = List.copyOf(selections);
    }
}
