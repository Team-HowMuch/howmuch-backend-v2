package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.notification", name = "delivery-mode", havingValue = "KAFKA")
public class NotificationDeliveryRelay {
    private final NotificationDeliveryStorePort store;
    private final NotificationDeliveryPublisherPort publisher;
    private final SettlementNotificationRetryPolicy retryPolicy;
    private final Clock clock;

    public BatchResult relayBatch(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("Positive relay batch size is required.");
        var now = now();
        int recovered = store.recoverStaleDeliveries(batchSize, retryPolicy.staleBefore(now), now, retryPolicy.maxAttempts());
        int claimed = 0, published = 0, retried = 0, lost = 0;
        while (claimed < batchSize) {
            now = now();
            var candidate = store.claimPublication(now, now.plusMinutes(2), now.minusMinutes(2));
            if (candidate.isEmpty()) break;
            claimed++;
            var claim = candidate.get();
            try {
                publisher.publish(claim.message());
            } catch (RuntimeException exception) {
                LocalDateTime failedAt = now();
                // Broker outages never consume provider attempts, and retain this generation/message ID.
                if (store.publicationFailed(claim, retryPolicy.nextAttemptAt(claim.attemptCount(), failedAt), failedAt)) retried++;
                else lost++;
                continue;
            }
            if (store.publicationSucceeded(claim, now())) published++;
            else lost++;
        }
        return new BatchResult(recovered, claimed, published, retried, lost);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public record BatchResult(int recovered, int claimed, int published, int retried, int ownershipLost) {}
}
