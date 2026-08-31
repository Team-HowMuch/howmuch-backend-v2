package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementExpenseJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;

public final class SettlementExpenseMapper {
    private SettlementExpenseMapper() {
    }

    public static SettlementExpenseJpaEntity toEntity(SettlementExpense expense) {
        return new SettlementExpenseJpaEntity(expense);
    }

    public static SettlementExpense toDomain(SettlementExpenseJpaEntity entity) {
        return new SettlementExpense(
                entity.getId(),
                entity.getSettlementId(),
                entity.getSource(),
                entity.getSourceReference(),
                entity.getSourceAccountId(),
                entity.getDescription(),
                entity.getAmount(),
                entity.getDisplayOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
