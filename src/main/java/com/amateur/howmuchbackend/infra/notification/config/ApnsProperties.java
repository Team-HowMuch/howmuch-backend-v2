package com.amateur.howmuchbackend.infra.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.notification.apns")
public record ApnsProperties(String keyId, String teamId, String privateKeyPath) {
}
