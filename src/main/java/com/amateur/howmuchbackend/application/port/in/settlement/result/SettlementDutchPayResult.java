package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.util.List;
import java.util.UUID;

public record SettlementDutchPayResult(
        UUID settlementId,
        long totalAmount,
        long allocatedAmount,
        long differenceAmount,
        boolean balanced,
        List<SettlementDutchPayParticipantResult> participants
) {
}
