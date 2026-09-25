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
        List<SettlementItemSelectionResult> selections,
        long discount,
        List<SettlementItemOptionResult> options
) {
    public SettlementItemAllocationItemResult(
            UUID itemId, String name, int quantity, long amount,
            int allocatedUnits, int totalUnits, List<SettlementItemSelectionResult> selections
    ) {
        this(itemId, name, quantity, amount, allocatedUnits, totalUnits,
                selections, 0, List.of());
    }

    public SettlementItemAllocationItemResult {
        selections = List.copyOf(selections);
        options = List.copyOf(options);
    }
}
