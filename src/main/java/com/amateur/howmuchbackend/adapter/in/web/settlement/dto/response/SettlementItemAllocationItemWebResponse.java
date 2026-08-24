package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationItemResult;

import java.util.List;
import java.util.UUID;

public record SettlementItemAllocationItemWebResponse(
        UUID itemId,
        String name,
        int quantity,
        long amount,
        int allocatedUnits,
        int totalUnits,
        List<SettlementItemSelectionWebResponse> selections
) {
    public static SettlementItemAllocationItemWebResponse from(
            SettlementItemAllocationItemResult result
    ) {
        return new SettlementItemAllocationItemWebResponse(
                result.itemId(),
                result.name(),
                result.quantity(),
                result.amount(),
                result.allocatedUnits(),
                result.totalUnits(),
                result.selections().stream()
                        .map(SettlementItemSelectionWebResponse::from)
                        .toList()
        );
    }
}
