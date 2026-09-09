package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;

import java.time.Duration;

public interface SmsVerificationStorePort {
    /** Atomically validates the current request and consumes it at most once, preserving its TTL. */
    SmsOtpState.Outcome verifyAndConsume(String phoneHash, String requestId, String codeHash, int maxAttempts);

    /** Atomically reserves a send, retaining cooldown independently of OTP verification lifetime. */
    boolean tryReserve(String phoneHash, SmsOtpState state, Duration otpTtl, Duration cooldown);

    /** Removes only the matching request after delivery failure; never releases its cooldown. */
    void discardRequest(String phoneHash, String requestId);
}
