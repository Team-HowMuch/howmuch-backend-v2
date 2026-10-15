package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("howmuch.settlement.category-classification")
public record SettlementCategoryClassificationProperties(boolean enabled, Duration pollInterval, int batchSize) {
    public SettlementCategoryClassificationProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        batchSize = batchSize == 0 ? 10 : batchSize;
        if (pollInterval.isNegative() || pollInterval.isZero() || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Category classification interval must be positive and batch size 1..100");
        }
    }
}
