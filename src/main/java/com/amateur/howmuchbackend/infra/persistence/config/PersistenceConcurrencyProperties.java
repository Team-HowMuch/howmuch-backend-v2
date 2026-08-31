package com.amateur.howmuchbackend.infra.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.persistence")
public record PersistenceConcurrencyProperties(Duration lockTimeout) {
    public PersistenceConcurrencyProperties {
        if (lockTimeout == null || lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("howmuch.persistence.lock-timeout must be positive");
        }
    }
}
