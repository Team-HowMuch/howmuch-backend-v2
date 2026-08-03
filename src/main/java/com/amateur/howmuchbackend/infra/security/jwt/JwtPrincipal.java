package com.amateur.howmuchbackend.infra.security.jwt;

public record JwtPrincipal(
        Long userId,
        String sessionId
) {}
