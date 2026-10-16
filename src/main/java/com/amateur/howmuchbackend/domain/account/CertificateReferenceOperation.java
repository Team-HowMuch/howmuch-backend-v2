package com.amateur.howmuchbackend.domain.account;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateReferenceOperation(
        UUID id,
        long userId,
        long connectionId,
        String sourceOrganization,
        String organization,
        UUID idempotencyKey,
        String requestHash,
        String connectionHash,
        CertificateReferenceStatus status,
        boolean alreadyConnected,
        String failureCode,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime providerStartedAt,
        LocalDateTime completedAt) {

    public boolean hasExecutionWindow(LocalDateTime now) {
        return expiresAt.isAfter(now.plusMinutes(6));
    }
}
