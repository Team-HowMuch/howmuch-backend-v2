package com.amateur.howmuchbackend.domain.auth.verification;

import com.amateur.howmuchbackend.domain.auth.identity.AuthIdentity;

import java.time.LocalDateTime;
import java.util.Objects;

public record SmsOtpState(
        String verificationRequestId,
        AuthIdentity identity,
        String codeHash,
        int attemptCount,
        LocalDateTime resendAvailableAt,
        LocalDateTime expiresAt
) {
    public SmsOtpState {
        Objects.requireNonNull(verificationRequestId);
        Objects.requireNonNull(identity);
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
                identity,
                codeHash,
                attemptCount + 1,
                resendAvailableAt,
                expiresAt
        );
    }
}
