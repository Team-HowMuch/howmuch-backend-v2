package com.amateur.howmuchbackend.application.port.out.auth.jwt;

import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;

import java.time.LocalDateTime;
import java.util.Objects;

public record JwtPayload(
        Long userId,
        String sessionId,
        JwtTokenType tokenType,
        LocalDateTime expiresAt
) {
    public JwtPayload {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(tokenType);
        Objects.requireNonNull(expiresAt);
        if (sessionId.isBlank()) throw new IllegalArgumentException("JWT sessionId must not be blank.");
    }

    public boolean isType(JwtTokenType expectedTokenType) {
        return tokenType == expectedTokenType;
    }
}
