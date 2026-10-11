package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateConnectionDecisionResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateConnectionDecisionWebResponse(
        UUID sessionId,
        UUID claimId,
        String organization,
        String status,
        Long connectionId,
        LocalDateTime expiresAt,
        String failureCode,
        String failureMessage) {
    public static CertificateConnectionDecisionWebResponse from(
            CertificateConnectionDecisionResult r) {
        return new CertificateConnectionDecisionWebResponse(
                r.sessionId(),
                r.claimId(),
                r.organization(),
                r.status(),
                r.connectionId(),
                r.expiresAt(),
                r.failureCode(),
                r.failureMessage());
    }
}
