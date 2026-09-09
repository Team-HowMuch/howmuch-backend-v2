package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.application.auth.model.LoginAttempt;

import java.time.Duration;
import java.util.Optional;

public interface LoginAttemptStorePort {
    /** Atomically consumes an attempt before credential verification, including in-flight requests. */
    Optional<LoginAttempt> reserve(String phoneHash, int maxAttempts, Duration lockDuration);

    /** Resets only the successful attempt's generation; expired or replaced state is preserved. */
    void resetAfterSuccess(String phoneHash, String generation);
}
