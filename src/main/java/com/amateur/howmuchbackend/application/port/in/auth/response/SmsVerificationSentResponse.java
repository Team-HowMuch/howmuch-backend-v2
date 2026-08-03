package com.amateur.howmuchbackend.application.port.in.auth.response;

import java.time.LocalDateTime;

public record SmsVerificationSentResponse(
        String verificationRequestId,
        String phoneNumber,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {}
