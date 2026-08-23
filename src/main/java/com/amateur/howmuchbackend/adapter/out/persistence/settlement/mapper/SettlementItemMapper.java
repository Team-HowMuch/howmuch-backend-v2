package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;

public final class SettlementItemMapper {
    private SettlementItemMapper() {
    }

    public static SettlementItemJpaEntity toEntity(SettlementItem item) {
        return new SettlementItemJpaEntity(item);
    }

    public static SettlementItem toDomain(SettlementItemJpaEntity entity) {
        return new SettlementItem(
                entity.getId(),
                entity.getSettlementId(),
                entity.getExpenseId(),
                entity.getName(),
                entity.getQuantity(),
                entity.getAmount(),
                entity.getDisplayOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
