package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;

import java.time.Duration;
import java.util.Optional;

public interface SmsVerificationStorePort {
    /** Atomically validates the current request and consumes it at most once, preserving its TTL. */
    SmsOtpState.Outcome verifyAndConsume(String phoneHash, String requestId, String codeHash, int maxAttempts);

    Optional<SmsOtpState> findByPhoneHash(String phoneHash);

    void save(String phoneHash, SmsOtpState state, Duration ttl);

    void deleteByPhoneHash(String phoneHash);
}
