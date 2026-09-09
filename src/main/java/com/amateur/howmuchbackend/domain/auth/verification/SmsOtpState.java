package com.amateur.howmuchbackend.domain.auth.verification;

import java.time.LocalDateTime;
import java.util.Objects;

public record SmsOtpState(
        String verificationRequestId,
        String phoneNumber,
        String codeHash,
        int attemptCount,
        LocalDateTime resendAvailableAt,
        LocalDateTime expiresAt,
        boolean consumed
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

    public SmsOtpState(String verificationRequestId, String phoneNumber, String codeHash,
                       int attemptCount, LocalDateTime resendAvailableAt, LocalDateTime expiresAt) {
        this(verificationRequestId, phoneNumber, codeHash, attemptCount, resendAvailableAt, expiresAt, false);
    }

    public Verification verify(String requestId, String submittedCodeHash, int maxAttempts) {
        if (!verificationRequestId.equals(requestId)) {
            return new Verification(Outcome.CODE_MISMATCH, this);
        }
        if (consumed) {
            return new Verification(Outcome.EXPIRED, this);
        }
        if (attemptCount >= maxAttempts) {
            return new Verification(Outcome.ATTEMPTS_EXCEEDED, this);
        }
        boolean matches = codeHash.equals(submittedCodeHash);
        int nextAttempts = matches ? attemptCount : attemptCount + 1;
        SmsOtpState next = new SmsOtpState(verificationRequestId, phoneNumber, codeHash,
                nextAttempts, resendAvailableAt, expiresAt, matches);
        Outcome outcome = matches ? Outcome.VERIFIED
                : nextAttempts >= maxAttempts ? Outcome.ATTEMPTS_EXCEEDED : Outcome.CODE_MISMATCH;
        return new Verification(outcome, next);
    }

    public enum Outcome { VERIFIED, CODE_MISMATCH, ATTEMPTS_EXCEEDED, EXPIRED }

    public record Verification(Outcome outcome, SmsOtpState state) {}
}
