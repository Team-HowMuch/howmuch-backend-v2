package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemSelectionResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;

import java.util.UUID;

public record SettlementItemSelectionWebResponse(
        UUID participantId,
        String participantDisplayName,
        SettlementItemAllocationFraction fraction,
        long amount
) {
    public static SettlementItemSelectionWebResponse from(
            SettlementItemSelectionResult result
    ) {
        return new SettlementItemSelectionWebResponse(
                result.participantId(),
                result.participantDisplayName(),
                result.fraction(),
                result.amount()
        );
    }
}
