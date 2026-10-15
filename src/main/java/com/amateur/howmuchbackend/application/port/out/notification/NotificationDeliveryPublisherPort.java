package com.amateur.howmuchbackend.application.port.out.notification;

import java.time.LocalDateTime;
import java.util.UUID;

public interface NotificationDeliveryPublisherPort {
    void publish(DeliveryMessage message);

    record DeliveryMessage(UUID messageId, UUID deliveryId, long generation,
            Long recipientUserId, LocalDateTime occurredAt) {}
}
