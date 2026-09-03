package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.settlement.notification-outbox")
public record SettlementNotificationOutboxProperties(
        Duration pollInterval,
        Duration initialDelay,
        int batchSize
) {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final int DEFAULT_BATCH_SIZE = 50;

    public SettlementNotificationOutboxProperties {
        pollInterval = defaultDuration(pollInterval, DEFAULT_POLL_INTERVAL);
        initialDelay = defaultDuration(initialDelay, DEFAULT_INITIAL_DELAY);
        batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;

        requirePositive(pollInterval, "poll interval");
        requirePositive(initialDelay, "initial delay");
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "Settlement notification Outbox batch size must be positive."
            );
        }
    }

    private static Duration defaultDuration(Duration value, Duration defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "Settlement notification Outbox " + name + " must be positive."
            );
        }
    }
}
