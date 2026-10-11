package com.amateur.howmuchbackend.application.certificate.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateClaim(
        UUID id,
        UUID sessionId,
        long userId,
        long generation,
        UUID idempotencyKey,
        String status,
        LocalDateTime expiresAt) {}
