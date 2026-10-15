package com.amateur.howmuchbackend.application.port.out.notification;

import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Durable provider ledger and independently leased broker publication capability. */
public interface NotificationDeliveryStorePort {
    Optional<PublicationClaim> claimPublication(LocalDateTime now, LocalDateTime leaseUntil, LocalDateTime republishBefore);
    boolean publicationSucceeded(PublicationClaim claim, LocalDateTime now);
    boolean publicationFailed(PublicationClaim claim, LocalDateTime retryAt, LocalDateTime now);
    Optional<SettlementNotificationOutbox> findReadyDelivery(UUID deliveryId, long generation, LocalDateTime now);
    Optional<NotificationDeliveryPublisherPort.DeliveryMessage> findDeliveryMessage(UUID deliveryId, long generation);
    boolean deferReadyDelivery(UUID deliveryId, long generation, LocalDateTime resumeAt, LocalDateTime now);
    Optional<DeliveryClaim> claimDelivery(UUID deliveryId, long generation, LocalDateTime now);
    boolean completeDelivery(DeliveryClaim claim, SettlementNotificationOutbox result, LocalDateTime now);
    int recoverStaleDeliveries(int batchSize, LocalDateTime staleBefore, LocalDateTime now, int maxAttempts);

    record PublicationClaim(NotificationDeliveryPublisherPort.DeliveryMessage message, UUID leaseToken, int attemptCount) {}
    record DeliveryClaim(SettlementNotificationOutbox event, long generation) {}
}
