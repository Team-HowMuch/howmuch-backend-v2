package com.amateur.howmuchbackend.domain.auth.challenge;

import com.amateur.howmuchbackend.domain.auth.identity.AuthIdentity;

import java.time.LocalDateTime;
import java.util.Objects;

public record AuthChallenge(
        String challengeId,
        AuthIdentity identity,
        int pinAttemptCount,
        LocalDateTime expiresAt
) {
    public AuthChallenge {
        Objects.requireNonNull(challengeId);
        Objects.requireNonNull(identity);
        Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public AuthChallenge increasePinAttemptCount() {
        return new AuthChallenge(challengeId, identity, pinAttemptCount + 1, expiresAt);
    }
}
