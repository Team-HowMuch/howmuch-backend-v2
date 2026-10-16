package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.CertificateReferenceOperationResult;
import com.amateur.howmuchbackend.domain.account.CertificateReferenceStatus;

import java.time.*;
import java.util.UUID;

public record CertificateReferenceOperationWebResponse(
        UUID operationId,
        long connectionId,
        String sourceOrganization,
        String organization,
        CertificateReferenceStatus status,
        boolean alreadyConnected,
        String failureCode,
        String failureMessage,
        Instant expiresAt,
        long pollAfterMs,
        Action action) {
    public record Action(String type, boolean supported) {}

    public static CertificateReferenceOperationWebResponse from(
            CertificateReferenceOperationResult r) {
        return new CertificateReferenceOperationWebResponse(
                r.operationId(),
                r.connectionId(),
                r.sourceOrganization(),
                r.organization(),
                r.status(),
                r.alreadyConnected(),
                r.failureCode(),
                r.failureMessage(),
                r.expiresAt().toInstant(ZoneOffset.UTC),
                r.pollAfterMs(),
                r.status() == CertificateReferenceStatus.ACTION_REQUIRED
                        ? new Action("ADDITIONAL_AUTH_REQUIRED", false)
                        : null);
    }
}
