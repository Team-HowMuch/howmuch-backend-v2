package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDutchPayAllocationJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;

public final class SettlementDutchPayAllocationMapper {
    private SettlementDutchPayAllocationMapper() {
    }

    public static SettlementDutchPayAllocationJpaEntity toEntity(
            SettlementDutchPayAllocation allocation
    ) {
        return new SettlementDutchPayAllocationJpaEntity(allocation);
    }

    public static SettlementDutchPayAllocation toDomain(
            SettlementDutchPayAllocationJpaEntity entity
    ) {
        return new SettlementDutchPayAllocation(
                entity.getId(),
                entity.getSettlementId(),
                entity.getParticipantId(),
                entity.getAmount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
