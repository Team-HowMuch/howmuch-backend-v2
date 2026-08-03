package com.amateur.howmuchbackend.infra.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.auth.pin")
public record PinProperties(int maxAttempts) {
}
