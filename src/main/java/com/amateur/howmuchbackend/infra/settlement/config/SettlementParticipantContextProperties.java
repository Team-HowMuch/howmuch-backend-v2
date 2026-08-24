package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.settlement.participant-context")
public record SettlementParticipantContextProperties(Duration ttl) {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    public SettlementParticipantContextProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Settlement participant context TTL must be positive.");
        }
    }
}
