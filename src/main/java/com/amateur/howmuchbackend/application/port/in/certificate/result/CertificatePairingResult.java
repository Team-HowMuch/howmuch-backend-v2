package com.amateur.howmuchbackend.application.port.in.certificate.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificatePairingResult(
        UUID id,
        String status,
        LocalDateTime expiresAt,
        String pcCapability,
        String qrToken,
        LocalDateTime qrExpiresAt,
        UUID sessionId,
        String organization,
        UUID claimId,
        String failureCode,
        String failureMessage) {
    @Override
    public String toString() {
        return "CertificatePairingResult[status=" + status + "]";
    }
}
