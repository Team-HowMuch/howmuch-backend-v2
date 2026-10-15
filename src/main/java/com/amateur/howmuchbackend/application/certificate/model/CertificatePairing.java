package com.amateur.howmuchbackend.application.certificate.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificatePairing(
        UUID id,
        String pcHash,
        String qrHash,
        LocalDateTime qrExpiresAt,
        LocalDateTime expiresAt,
        Long userId,
        String organization,
        UUID sessionId,
        boolean authenticationPrepared,
        Long existingConnectionId,
        LocalDateTime cancelledAt) {
    @Override
    public String toString() {
        return "CertificatePairing[id=" + id + "]";
    }
}
