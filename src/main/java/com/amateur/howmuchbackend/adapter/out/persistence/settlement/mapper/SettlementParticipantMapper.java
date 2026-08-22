package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;

public final class SettlementParticipantMapper {
    private SettlementParticipantMapper() {
    }

    public static SettlementParticipantJpaEntity toEntity(SettlementParticipant participant) {
        return new SettlementParticipantJpaEntity(participant);
    }

    public static SettlementParticipant toDomain(SettlementParticipantJpaEntity entity) {
        return new SettlementParticipant(
                entity.getId(),
                entity.getSettlementId(),
                entity.getSource(),
                entity.getLinkedUserId(),
                entity.getPhoneHash(),
                entity.getDisplayName(),
                entity.getDisplayOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
