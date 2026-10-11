package com.amateur.howmuchbackend.domain.certificate;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateSession(
        UUID id,
        long version,
        String organization,
        String status,
        String pcHash,
        UUID uploadRef,
        UUID connectionRef,
        long claimGeneration,
        String qrHash,
        LocalDateTime qrExpiresAt,
        UUID currentClaimId,
        UUID approvalId,
        Long approvedUserId,
        UUID ownershipRef,
        LocalDateTime expiresAt,
        String failureCode) {
    public boolean approved() {
        return approvalId != null;
    }

    public boolean terminal() {
        return approved() || java.util.Set.of("FAILED", "EXPIRED", "CANCELLED").contains(status);
    }

    @Override
    public String toString() {
        return "CertificateSession[id=" + id + ",status=" + status + "]";
    }
}
