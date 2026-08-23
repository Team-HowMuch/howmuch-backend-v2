package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.Settlement;

public final class SettlementMapper {
    private SettlementMapper() {
    }

    public static SettlementJpaEntity toEntity(Settlement settlement) {
        return new SettlementJpaEntity(settlement);
    }

    public static Settlement toDomain(SettlementJpaEntity entity) {
        return new Settlement(
                entity.getId(),
                entity.getCreatorId(),
                entity.getTitle(),
                entity.getTotalAmount(),
                entity.getAllocationType(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
