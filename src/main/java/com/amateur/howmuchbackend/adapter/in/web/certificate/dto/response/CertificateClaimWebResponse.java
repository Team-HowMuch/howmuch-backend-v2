package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateClaimResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateClaimWebResponse(
        UUID claimId, String status, String confirmationCode, LocalDateTime expiresAt) {
    public static CertificateClaimWebResponse from(CertificateClaimResult r) {
        return new CertificateClaimWebResponse(
                r.claimId(), r.status(), r.confirmationCode(), r.expiresAt());
    }

    @Override
    public String toString() {
        return "CertificateClaimWebResponse[redacted]";
    }
}
