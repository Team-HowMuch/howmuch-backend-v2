package com.amateur.howmuchbackend.infra.codef.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.codef")
public record CodefProperties(
        String baseUrl,
        String oauthUrl,
        String clientId,
        String clientSecret,
        String publicKey,
        String connectedIdEncryptionKey,
        Duration connectTimeout,
        Duration readTimeout
) {
    public CodefProperties {
        baseUrl = defaultIfBlank(baseUrl, "https://development.codef.io");
        oauthUrl = defaultIfBlank(oauthUrl, "https://oauth.codef.io/oauth/token");
        clientId = defaultIfBlank(clientId, "");
        clientSecret = defaultIfBlank(clientSecret, "");
        publicKey = defaultIfBlank(publicKey, "");
        connectedIdEncryptionKey = defaultIfBlank(connectedIdEncryptionKey, "");
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(305) : readTimeout;
    }

    public boolean hasClientCredentials() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    public boolean hasPublicKey() {
        return StringUtils.hasText(publicKey);
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
