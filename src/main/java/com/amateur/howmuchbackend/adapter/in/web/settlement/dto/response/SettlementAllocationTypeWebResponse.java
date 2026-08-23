package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationNextStep;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationTypeResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;

import java.util.UUID;

public record SettlementAllocationTypeWebResponse(
        UUID settlementId,
        SettlementAllocationType allocationType,
        SettlementAllocationNextStep nextStep
) {
    public static SettlementAllocationTypeWebResponse from(
            SettlementAllocationTypeResult result
    ) {
        return new SettlementAllocationTypeWebResponse(
                result.settlementId(),
                result.allocationType(),
                result.nextStep()
        );
    }
}
