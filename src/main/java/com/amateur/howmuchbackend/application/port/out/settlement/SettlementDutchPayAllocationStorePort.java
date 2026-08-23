package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementDutchPayAllocationStorePort {
    List<SettlementDutchPayAllocation> findAllBySettlementId(UUID settlementId);

    Optional<SettlementDutchPayAllocation> findBySettlementIdAndParticipantId(
            UUID settlementId,
            UUID participantId
    );

    List<SettlementDutchPayAllocation> replaceAll(
            UUID settlementId,
            List<SettlementDutchPayAllocation> allocations
    );

    SettlementDutchPayAllocation update(SettlementDutchPayAllocation allocation);

    void deleteAllBySettlementId(UUID settlementId);
}
