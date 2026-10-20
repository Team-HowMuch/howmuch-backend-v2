package com.amateur.howmuchbackend.infra.settlement.recurring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("howmuch.settlement.recurring")
public record RecurringSettlementProperties(Duration pollInterval, int batchSize) {
    public RecurringSettlementProperties {
        pollInterval = pollInterval == null ? Duration.ofMinutes(1) : pollInterval;
        batchSize = batchSize == 0 ? 50 : batchSize;
        if (pollInterval.isNegative() || pollInterval.isZero() || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Recurring interval must be positive; batch size must be 1..1000");
        }
    }
}
