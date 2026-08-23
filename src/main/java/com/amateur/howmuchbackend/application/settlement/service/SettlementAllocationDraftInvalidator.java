package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SettlementAllocationDraftInvalidator {
    private final SettlementDutchPayAllocationStorePort allocationStorePort;

    public Settlement invalidate(Settlement settlement, LocalDateTime now) {
        if (settlement.allocationType() == null) {
            return settlement;
        }
        allocationStorePort.deleteAllBySettlementId(settlement.id());
        return settlement.clearAllocation(now);
    }
}
