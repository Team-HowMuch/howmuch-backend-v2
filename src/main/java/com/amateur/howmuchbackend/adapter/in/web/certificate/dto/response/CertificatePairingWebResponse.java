package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificatePairingResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificatePairingWebResponse(
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
    public static CertificatePairingWebResponse from(CertificatePairingResult r) {
        return new CertificatePairingWebResponse(
                r.id(),
                r.status(),
                r.expiresAt(),
                r.pcCapability(),
                r.qrToken(),
                r.qrExpiresAt(),
                r.sessionId(),
                r.organization(),
                r.claimId(),
                r.failureCode(),
                r.failureMessage());
    }

    @Override
    public String toString() {
        return "CertificatePairingWebResponse[status=" + status + "]";
    }
}
