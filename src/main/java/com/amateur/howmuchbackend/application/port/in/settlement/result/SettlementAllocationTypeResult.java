package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;

import java.util.UUID;

public record SettlementAllocationTypeResult(
        UUID settlementId,
        SettlementAllocationType allocationType,
        SettlementAllocationNextStep nextStep
) {
    public static SettlementAllocationTypeResult of(
            UUID settlementId,
            SettlementAllocationType allocationType
    ) {
        SettlementAllocationNextStep nextStep = switch (allocationType) {
            case DUTCH_PAY -> SettlementAllocationNextStep.REVIEW_DUTCH_PAY;
            case ITEM_ALLOCATION -> SettlementAllocationNextStep.REVIEW_ITEM_ALLOCATION;
        };
        return new SettlementAllocationTypeResult(settlementId, allocationType, nextStep);
    }
}
