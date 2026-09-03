package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.settlement.notification-outbox")
public record SettlementNotificationOutboxProperties(
        Duration pollInterval,
        Duration initialDelay,
        int batchSize,
        int maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Duration processingTimeout
) {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(1);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofMinutes(2);

    public SettlementNotificationOutboxProperties {
        pollInterval = defaultDuration(pollInterval, DEFAULT_POLL_INTERVAL);
        initialDelay = defaultDuration(initialDelay, DEFAULT_INITIAL_DELAY);
        batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxAttempts = maxAttempts == 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        retryBaseDelay = defaultDuration(retryBaseDelay, DEFAULT_RETRY_BASE_DELAY);
        retryMaxDelay = defaultDuration(retryMaxDelay, DEFAULT_RETRY_MAX_DELAY);
        processingTimeout = defaultDuration(processingTimeout, DEFAULT_PROCESSING_TIMEOUT);

        requirePositive(pollInterval, "poll interval");
        requirePositive(initialDelay, "initial delay");
        requirePositive(retryBaseDelay, "retry base delay");
        requirePositive(retryMaxDelay, "retry max delay");
        requirePositive(processingTimeout, "processing timeout");
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "Settlement notification Outbox batch size must be positive."
            );
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "Settlement notification Outbox max attempts must be positive."
            );
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                    "Settlement notification Outbox max retry delay must not be shorter than base delay."
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
