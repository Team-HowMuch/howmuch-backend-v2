package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.settlement.service.SettlementNotificationOutboxWorker;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementNotificationOutboxProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.notification-outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SettlementNotificationOutboxScheduler {
    private final SettlementNotificationOutboxWorker worker;
    private final SettlementNotificationOutboxProperties properties;
    private final MeterRegistry registry;

    @Scheduled(
            fixedDelayString = "${howmuch.settlement.notification-outbox.poll-interval:1s}",
            initialDelayString = "${howmuch.settlement.notification-outbox.initial-delay:1s}"
    )
    public void poll() {
        try {
            SettlementNotificationOutboxWorker.BatchResult result =
                    worker.processBatch(properties.batchSize());
            record("recovered", result.recovered());
            record("claimed", result.claimed());
            record("succeeded", result.succeeded());
            record("retried", result.retried());
            record("failed", result.failed());
            record("ownership_lost", result.ownershipLost());
            if (result.recovered() > 0 || result.claimed() > 0) {
                log.info(
                        "Processed settlement notification Outbox batch. "
                                + "recovered={}, claimed={}, succeeded={}, retried={}, failed={}, ownershipLost={}",
                        result.recovered(),
                        result.claimed(),
                        result.succeeded(),
                        result.retried(),
                        result.failed(),
                        result.ownershipLost()
                );
            }
        } catch (RuntimeException exception) {
            registry.counter("howmuch.settlement.outbox.poll.failures").increment();
            log.error(
                    "Settlement notification Outbox polling failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private void record(String outcome, int count) {
        registry.counter("howmuch.settlement.outbox.events", "outcome", outcome).increment(count);
    }
}
