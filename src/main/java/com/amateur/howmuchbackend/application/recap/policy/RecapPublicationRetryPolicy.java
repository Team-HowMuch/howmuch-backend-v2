package com.amateur.howmuchbackend.application.recap.policy;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RecapPublicationRetryPolicy {
    public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
        return now.plusSeconds(Math.min(300L, Math.max(1, attemptCount) * 10L));
    }
}
