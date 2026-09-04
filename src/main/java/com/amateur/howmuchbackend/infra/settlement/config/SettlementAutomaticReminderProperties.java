package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("howmuch.settlement.automatic-reminder")
public record SettlementAutomaticReminderProperties(boolean enabled, Duration pollInterval, int batchSize) {
    public SettlementAutomaticReminderProperties {
        pollInterval = pollInterval == null ? Duration.ofMinutes(1) : pollInterval;
        batchSize = batchSize == 0 ? 50 : batchSize;
        if (pollInterval.isNegative() || pollInterval.isZero() || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Automatic reminder interval must be positive and batch size must be 1..1000");
        }
    }
}
