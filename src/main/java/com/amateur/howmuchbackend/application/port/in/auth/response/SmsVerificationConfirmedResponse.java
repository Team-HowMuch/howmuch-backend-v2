package com.amateur.howmuchbackend.application.port.in.auth.response;

import java.time.LocalDateTime;

public record SmsVerificationConfirmedResponse(
        String challengeId,
        LocalDateTime expiresAt
) {
}
