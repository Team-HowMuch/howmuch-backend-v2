package com.amateur.howmuchbackend.application.port.in.certificate.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateClaimResult(
        UUID claimId, String status, String confirmationCode, LocalDateTime expiresAt) {
    @Override
    public String toString() {
        return "CertificateClaimResult[redacted]";
    }
}
