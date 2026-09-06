package com.amateur.howmuchbackend.application.port.in.auth.result;

import java.time.LocalDateTime;

public record SmsVerificationSentResult(
        String verificationRequestId,
        String phoneNumber,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {

    @Override
    public String toString() {
        return "SmsVerificationSentResult[verificationRequestId=***, phoneNumber=***, expiresAt=%s, resendAvailableAt=%s]"
                .formatted(expiresAt, resendAvailableAt);
    }
}
