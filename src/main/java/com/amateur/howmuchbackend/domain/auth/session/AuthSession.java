package com.amateur.howmuchbackend.domain.auth.session;

import java.time.LocalDateTime;
import java.util.Objects;

public record AuthSession(
        Long userId,
        String sessionId,
        String refreshToken,
        LocalDateTime expiresAt
) {
    public AuthSession {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(refreshToken);
        Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean matches(String sessionId, String refreshToken) {
        return this.sessionId.equals(sessionId) && this.refreshToken.equals(refreshToken);
    }
}
