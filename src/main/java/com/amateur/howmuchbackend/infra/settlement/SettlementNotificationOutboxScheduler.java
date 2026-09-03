package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.settlement.service.SettlementNotificationOutboxWorker;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementNotificationOutboxProperties;
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

    @Scheduled(
            fixedDelayString = "${howmuch.settlement.notification-outbox.poll-interval:1s}",
            initialDelayString = "${howmuch.settlement.notification-outbox.initial-delay:1s}"
    )
    public void poll() {
        try {
            SettlementNotificationOutboxWorker.BatchResult result =
                    worker.processBatch(properties.batchSize());
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
            log.error(
                    "Settlement notification Outbox polling failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
