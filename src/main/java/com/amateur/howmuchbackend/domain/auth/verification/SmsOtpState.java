package com.amateur.howmuchbackend.domain.auth.verification;

import java.time.LocalDateTime;
import java.util.Objects;

public record SmsOtpState(
        String verificationRequestId,
        String phoneNumber,
        String codeHash,
        int attemptCount,
        LocalDateTime resendAvailableAt,
        LocalDateTime expiresAt
) {
    public SmsOtpState {
        Objects.requireNonNull(verificationRequestId);
        Objects.requireNonNull(phoneNumber);
        Objects.requireNonNull(codeHash);
        Objects.requireNonNull(resendAvailableAt);
        Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean canResend(LocalDateTime now) {
        return !resendAvailableAt.isAfter(now);
    }

    public SmsOtpState increaseAttemptCount() {
        return new SmsOtpState(
                verificationRequestId,
                phoneNumber,
                codeHash,
                attemptCount + 1,
                resendAvailableAt,
                expiresAt
        );
    }
}
