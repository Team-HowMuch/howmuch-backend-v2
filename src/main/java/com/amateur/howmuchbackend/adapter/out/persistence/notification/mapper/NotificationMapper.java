package com.amateur.howmuchbackend.adapter.out.persistence.notification.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import java.util.UUID;

public final class NotificationMapper {
    private NotificationMapper() {}
    public static UserNotification toDomain(UserNotificationJpaEntity e, UUID visibleSettlementId) {
        return new UserNotification(e.getId(), e.getRecipientUserId(), visibleSettlementId, e.getNotificationType(),
                e.getAllocationType(), e.getRequesterName(), e.getTitle(), e.getMessage(), e.getDeduplicationKey(),
                e.getReadAt(), e.getCreatedAt());
    }
}
