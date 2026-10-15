package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.notification.NotificationTarget;

import java.util.UUID;

public interface SettlementPushSenderPort {
    SendResult sendParticipationRequest(
            String firebaseInstallationId,
            UUID settlementId,
            String shareLink
    );

    SendResult sendNotification(String firebaseInstallationId, NotificationMessage message);

    record NotificationMessage(UUID notificationId, UUID settlementId, String type,
            SettlementAllocationType allocationType, String title, String body, NotificationTarget target) {
        public NotificationMessage(UUID notificationId, UUID settlementId, String type,
                SettlementAllocationType allocationType, String title, String body) {
            this(notificationId, settlementId, type, allocationType, title, body, null);
        }
    }

    enum SendResult {
        SENT,
        INVALID_TARGET
    }
}
