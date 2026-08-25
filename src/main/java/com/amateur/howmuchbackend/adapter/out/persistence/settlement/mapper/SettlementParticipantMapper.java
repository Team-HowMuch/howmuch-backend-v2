package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

public final class SettlementParticipantMapper {
    private SettlementParticipantMapper() {
    }

    public static SettlementParticipantJpaEntity toEntity(SettlementParticipant participant) {
        return new SettlementParticipantJpaEntity(participant);
    }

    public static SettlementParticipant toDomain(SettlementParticipantJpaEntity entity) {
        SettlementParticipantSource source = entity.getSource() == SettlementParticipantSource.GROUP_MEMBER
                && entity.getSourceGroupId() == null
                ? SettlementParticipantSource.REGISTERED_USER
                : entity.getSource();
        return new SettlementParticipant(
                entity.getId(),
                entity.getSettlementId(),
                source,
                entity.getLinkedUserId(),
                entity.getPhoneHash(),
                entity.getSourceGroupId(),
                entity.getDisplayName(),
                entity.getDisplayOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
