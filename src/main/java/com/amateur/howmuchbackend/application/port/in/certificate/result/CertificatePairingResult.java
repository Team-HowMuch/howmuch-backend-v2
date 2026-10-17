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
        String failureMessage,
        com.amateur.howmuchbackend.application.port.in.external.result
                        .AdditionalAuthenticationResult
                additionalAuthentication) {
    public CertificatePairingResult(
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
        this(
                id,
                status,
                expiresAt,
                pcCapability,
                qrToken,
                qrExpiresAt,
                sessionId,
                organization,
                claimId,
                failureCode,
                failureMessage,
                null);
    }

    @Override
    public String toString() {
        return "CertificatePairingResult[status=" + status + "]";
    }
}
