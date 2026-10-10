package com.amateur.howmuchbackend.infra.notification;

import com.amateur.howmuchbackend.application.notification.service.NotificationDeliveryRelay;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementNotificationOutboxProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression("${howmuch.settlement.notification-outbox.enabled:true}")
@ConditionalOnProperty(prefix = "howmuch.notification", name = "delivery-mode", havingValue = "KAFKA")
public class NotificationDeliveryRelayScheduler {
    private final NotificationDeliveryRelay relay;
    private final SettlementNotificationOutboxProperties properties;
    private final MeterRegistry registry;
    @Scheduled(fixedDelayString = "${howmuch.settlement.notification-outbox.poll-interval:1s}",
            initialDelayString = "${howmuch.settlement.notification-outbox.initial-delay:1s}")
    public void poll() {
        try {
            var result = relay.relayBatch(properties.batchSize());
            record("recovered", result.recovered()); record("claimed", result.claimed());
            record("published", result.published()); record("retried", result.retried());
            record("ownership_lost", result.ownershipLost());
        } catch (RuntimeException exception) {
            registry.counter("howmuch.notification.publication.poll.failures").increment();
            log.error("Notification publication polling failed. failureType={}", exception.getClass().getSimpleName());
        }
    }
    private void record(String outcome, int count) {
        registry.counter("howmuch.notification.publication.events", "outcome", outcome).increment(count);
    }
}
