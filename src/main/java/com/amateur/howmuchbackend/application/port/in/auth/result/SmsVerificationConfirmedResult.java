package com.amateur.howmuchbackend.application.port.in.auth.result;

import java.time.LocalDateTime;

public record SmsVerificationConfirmedResult(
        String challengeId,
        LocalDateTime expiresAt
) {

    @Override
    public String toString() {
        return "SmsVerificationConfirmedResult[challengeId=***, expiresAt=%s]"
                .formatted(expiresAt);
    }
}
