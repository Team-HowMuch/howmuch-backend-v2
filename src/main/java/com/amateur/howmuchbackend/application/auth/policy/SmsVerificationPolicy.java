package com.amateur.howmuchbackend.application.auth.policy;

import java.time.Duration;

public record SmsVerificationPolicy(
        Duration otpTtl,
        Duration challengeTtl,
        Duration resendCooldown,
        int maxAttempts
) {
    public SmsVerificationPolicy {
        if (otpTtl == null) otpTtl = Duration.ofMinutes(3);
        if (challengeTtl == null) challengeTtl = Duration.ofMinutes(30);
        if (resendCooldown == null) resendCooldown = Duration.ofSeconds(60);
        if (maxAttempts <= 0) maxAttempts = 5;
    }
}
