package com.amateur.howmuchbackend.application.port.in.auth.result;

import java.time.LocalDateTime;

public record SmsVerificationConfirmedResult(
        String challengeId,
        LocalDateTime expiresAt
) {
}
