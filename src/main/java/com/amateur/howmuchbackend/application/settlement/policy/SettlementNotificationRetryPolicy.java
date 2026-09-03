package com.amateur.howmuchbackend.application.settlement.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

public class SettlementNotificationRetryPolicy {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration processingTimeout;

    public SettlementNotificationRetryPolicy(
            int maxAttempts,
            Duration baseDelay,
            Duration maxDelay,
            Duration processingTimeout
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Settlement notification max attempts must be positive.");
        }
        this.baseDelay = requirePositive(baseDelay, "base delay");
        this.maxDelay = requirePositive(maxDelay, "max delay");
        this.processingTimeout = requirePositive(processingTimeout, "processing timeout");
        if (this.maxDelay.compareTo(this.baseDelay) < 0) {
            throw new IllegalArgumentException(
                    "Settlement notification max delay must not be shorter than base delay."
            );
        }
        this.maxAttempts = maxAttempts;
    }

    public boolean canRetry(int attemptCount) {
        return attemptCount < maxAttempts;
    }

    public LocalDateTime nextAttemptAt(int attemptCount, LocalDateTime now) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("Settlement notification attempt count must be positive.");
        }
        Objects.requireNonNull(now, "Settlement notification failure time is required.");
        Duration delay = baseDelay;
        for (int currentAttempt = 1; currentAttempt < attemptCount; currentAttempt++) {
            if (delay.compareTo(maxDelay.dividedBy(2)) > 0) {
                delay = maxDelay;
                break;
            }
            delay = delay.multipliedBy(2);
        }
        if (delay.compareTo(maxDelay) > 0) {
            delay = maxDelay;
        }
        return now.plus(delay);
    }

    public LocalDateTime staleBefore(LocalDateTime now) {
        return Objects.requireNonNull(now, "Settlement notification recovery time is required.")
                .minus(processingTimeout);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    private Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, "Settlement notification " + name + " is required.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "Settlement notification " + name + " must be positive."
            );
        }
        return duration;
    }
}
