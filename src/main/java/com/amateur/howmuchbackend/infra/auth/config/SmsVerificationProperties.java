package com.amateur.howmuchbackend.infra.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.auth.sms")
public record SmsVerificationProperties(
        Duration otpTtl,
        Duration challengeTtl,
        Duration resendCooldown,
        int maxAttempts
) {
}
