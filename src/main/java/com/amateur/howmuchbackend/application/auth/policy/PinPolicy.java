package com.amateur.howmuchbackend.application.auth.policy;

import java.util.regex.Pattern;

public record PinPolicy(int maxAttempts) {
    private static final Pattern PIN_PATTERN = Pattern.compile("^\\d{6}$");

    public PinPolicy {
        if (maxAttempts <= 0) maxAttempts = 5;
    }

    public boolean isValid(String pin) {
        return pin != null && PIN_PATTERN.matcher(pin).matches();
    }
}
