package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.CertificateReferenceStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateReferenceOperationResult(
        UUID operationId,
        long connectionId,
        String sourceOrganization,
        String organization,
        CertificateReferenceStatus status,
        boolean alreadyConnected,
        String failureCode,
        String failureMessage,
        LocalDateTime expiresAt,
        long pollAfterMs) {}
