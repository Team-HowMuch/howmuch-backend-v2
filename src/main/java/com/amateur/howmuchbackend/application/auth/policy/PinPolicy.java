package com.amateur.howmuchbackend.application.auth.policy;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

public record PinPolicy(int maxAttempts, Duration lockDuration) {
    private static final Pattern PIN_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Set<String> BLOCKED_PINS = Set.of("112233", "123456", "654321");

    public PinPolicy {
        if (maxAttempts <= 0) maxAttempts = 5;
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            lockDuration = Duration.ofMinutes(30);
        }
    }

    public boolean isValid(String pin) {
        return isFormatValid(pin)
                && !pin.chars().allMatch(character -> character == pin.charAt(0))
                && !BLOCKED_PINS.contains(pin);
    }

    public boolean isFormatValid(String pin) {
        return pin != null && PIN_PATTERN.matcher(pin).matches();
    }
}
