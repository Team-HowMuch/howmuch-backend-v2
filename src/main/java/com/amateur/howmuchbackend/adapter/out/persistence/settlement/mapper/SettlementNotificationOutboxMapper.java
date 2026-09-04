package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementNotificationOutboxJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;

public final class SettlementNotificationOutboxMapper {
    private SettlementNotificationOutboxMapper() {
    }

    public static SettlementNotificationOutboxJpaEntity toEntity(
            SettlementNotificationOutbox event
    ) {
        return new SettlementNotificationOutboxJpaEntity(event);
    }

    public static SettlementNotificationOutbox toDomain(
            SettlementNotificationOutboxJpaEntity entity
    ) {
        return new SettlementNotificationOutbox(
                entity.getId(),
                entity.getSettlementId(),
                entity.getRecipientUserId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getProcessingStartedAt(),
                entity.getCompletedAt(),
                entity.getLastFailureCode(),
                entity.getNotificationId()
        );
    }
}
