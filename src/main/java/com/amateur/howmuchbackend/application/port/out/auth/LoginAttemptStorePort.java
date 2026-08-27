package com.amateur.howmuchbackend.application.port.out.auth;

import java.time.Duration;

public interface LoginAttemptStorePort {
    long getFailureCount(String phoneHash);

    long increaseFailureCount(String phoneHash, int maxAttempts, Duration lockDuration);

    void deleteByPhoneHash(String phoneHash);
}
