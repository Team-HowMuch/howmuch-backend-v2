package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.settlement.service.SettlementNotificationOutboxWorker;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementNotificationOutboxProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    private final ObservationRegistry observationRegistry;

    @Scheduled(
            fixedDelayString = "${howmuch.settlement.notification-outbox.poll-interval:1s}",
            initialDelayString = "${howmuch.settlement.notification-outbox.initial-delay:1s}"
    )
    public void poll() {
        Observation observation = Observation.createNotStarted(
                        "howmuch.settlement.notification.outbox.poll", observationRegistry)
                .lowCardinalityKeyValue("component", "settlement-outbox")
                .start();
        try {
            SettlementNotificationOutboxWorker.BatchResult result =
                    worker.processBatch(properties.batchSize());
            observation.lowCardinalityKeyValue("outcome", "processed");
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
            observation.lowCardinalityKeyValue("outcome", "failed");
            registry.counter("howmuch.settlement.outbox.poll.failures").increment();
            log.error(
                    "Settlement notification Outbox polling failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
        } finally {
            observation.stop();
        }
    }

    private void record(String outcome, int count) {
        registry.counter("howmuch.settlement.outbox.events", "outcome", outcome).increment(count);
    }
}
