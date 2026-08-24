package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.settlement.link")
public record SettlementLinkProperties(
        String baseUrl,
        Duration ttl
) {
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    public SettlementLinkProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Settlement link base URL is required.");
        }
        baseUrl = normalizeBaseUrl(baseUrl);
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Settlement link TTL must be positive.");
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.strip();
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Settlement link base URL must be an absolute HTTP URL.");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
