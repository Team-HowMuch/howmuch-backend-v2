package com.amateur.howmuchbackend.infra.group.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "howmuch.group.invite")
public record GroupInviteProperties(String baseUrl) {
    public GroupInviteProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Group invite base URL is required.");
        }
        baseUrl = normalizeBaseUrl(baseUrl);
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.strip();
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("https".equalsIgnoreCase(scheme)
                || ("http".equalsIgnoreCase(scheme) && isLoopbackHost(uri.getHost())))) {
            throw new IllegalArgumentException(
                    "Group invite base URL must use HTTPS except for a loopback development host."
            );
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized);
    }
}
