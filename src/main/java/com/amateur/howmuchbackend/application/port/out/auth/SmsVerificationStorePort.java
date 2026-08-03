package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;

import java.time.Duration;
import java.util.Optional;

public interface SmsVerificationStorePort {
    Optional<SmsOtpState> findByPhoneHash(String phoneHash);

    void save(String phoneHash, SmsOtpState state, Duration ttl);

    void deleteByPhoneHash(String phoneHash);
}
