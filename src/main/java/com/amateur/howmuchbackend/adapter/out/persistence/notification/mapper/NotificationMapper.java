package com.amateur.howmuchbackend.adapter.out.persistence.notification.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import com.amateur.howmuchbackend.domain.notification.UserNotification;

public final class NotificationMapper {
    private NotificationMapper() {}
    public static UserNotification toDomain(UserNotificationJpaEntity e) {
        return new UserNotification(e.getId(), e.getRecipientUserId(), e.getSettlementId(), e.getNotificationType(),
                e.getAllocationType(), e.getRequesterName(), e.getTitle(), e.getMessage(), e.getDeduplicationKey(),
                e.getReadAt(), e.getCreatedAt());
    }
}
