package com.amateur.howmuchbackend.application.port.in.auth.result;

import java.time.LocalDateTime;

public record SmsVerificationSentResult(
        String verificationRequestId,
        String phoneNumber,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {
}
