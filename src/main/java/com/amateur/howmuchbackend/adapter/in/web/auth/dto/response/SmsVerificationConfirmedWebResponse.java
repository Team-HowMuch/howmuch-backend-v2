package com.amateur.howmuchbackend.adapter.in.web.auth.dto.response;

import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult;

import java.time.LocalDateTime;

public record SmsVerificationConfirmedWebResponse(
        String challengeId,
        LocalDateTime expiresAt
) {
    public static SmsVerificationConfirmedWebResponse from(SmsVerificationConfirmedResult result) {
        return new SmsVerificationConfirmedWebResponse(result.challengeId(), result.expiresAt());
    }

    @Override
    public String toString() {
        return "SmsVerificationConfirmedWebResponse[challengeId=***, expiresAt=%s]"
                .formatted(expiresAt);
    }
}
