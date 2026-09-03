package com.amateur.howmuchbackend.application.settlement.policy;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Component
public class SettlementNotificationRetryPolicy {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_DELAY = Duration.ofMinutes(1);
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(2);

    public boolean canRetry(int attemptCount) {
        return attemptCount < MAX_ATTEMPTS;
    }

    public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("Settlement notification attempt count must be positive.");
        }
        Objects.requireNonNull(now, "Settlement notification failure time is required.");
        Duration delay = BASE_DELAY;
        for (int currentAttempt = 1; currentAttempt < attemptCount; currentAttempt++) {
            if (delay.compareTo(MAX_DELAY.dividedBy(2)) > 0) {
                delay = MAX_DELAY;
                break;
            }
            delay = delay.multipliedBy(2);
        }
        if (delay.compareTo(MAX_DELAY) > 0) {
            delay = MAX_DELAY;
        }
        return now.plus(delay);
    }

    public LocalDateTime staleBefore(LocalDateTime now) {
        return Objects.requireNonNull(now, "Settlement notification recovery time is required.")
                .minus(PROCESSING_TIMEOUT);
    }

    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }
}
