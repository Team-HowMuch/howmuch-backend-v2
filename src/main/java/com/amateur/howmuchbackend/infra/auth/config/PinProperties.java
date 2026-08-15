package com.amateur.howmuchbackend.infra.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.auth.pin")
public record PinProperties(int maxAttempts, Duration lockDuration) {
}
