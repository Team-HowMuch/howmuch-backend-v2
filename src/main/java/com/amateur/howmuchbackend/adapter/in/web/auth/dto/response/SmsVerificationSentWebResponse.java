package com.amateur.howmuchbackend.adapter.in.web.auth.dto.response;

import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationSentResult;

import java.time.LocalDateTime;

public record SmsVerificationSentWebResponse(
        String verificationRequestId,
        String phoneNumber,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {
    public static SmsVerificationSentWebResponse from(SmsVerificationSentResult result) {
        return new SmsVerificationSentWebResponse(
                result.verificationRequestId(),
                result.phoneNumber(),
                result.expiresAt(),
                result.resendAvailableAt()
        );
    }

    @Override
    public String toString() {
        return "SmsVerificationSentWebResponse[verificationRequestId=***, phoneNumber=***, expiresAt=%s, resendAvailableAt=%s]"
                .formatted(expiresAt, resendAvailableAt);
    }
}
