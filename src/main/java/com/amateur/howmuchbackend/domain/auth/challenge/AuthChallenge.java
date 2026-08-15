package com.amateur.howmuchbackend.domain.auth.challenge;

import java.time.LocalDateTime;
import java.util.Objects;

public record AuthChallenge(
        String challengeId,
        String phoneNumber,
        LocalDateTime expiresAt
) {
    public AuthChallenge {
        Objects.requireNonNull(challengeId);
        Objects.requireNonNull(phoneNumber);
        Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}
