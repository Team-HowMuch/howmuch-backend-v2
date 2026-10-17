package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.application.certificate.model.*;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdditionalAuthenticationChallenge(
        UUID id,
        long userId,
        UUID sessionId,
        String organization,
        long generation,
        String status,
        String maskedDestination,
        String failureCode,
        UUID submissionId,
        UUID leaseToken,
        long leaseGeneration,
        boolean requestStarted,
        boolean cancelRequested,
        LocalDateTime expiresAt,
        LocalDateTime leaseExpiresAt,
        CertificateEnvelope payload,
        CertificateExecution execution,
        String operationType,
        Long connectionId) {
    public AdditionalAuthenticationChallenge(
            UUID id,
            long userId,
            UUID sessionId,
            String organization,
            long generation,
            String status,
            String maskedDestination,
            String failureCode,
            UUID submissionId,
            UUID leaseToken,
            long leaseGeneration,
            boolean requestStarted,
            boolean cancelRequested,
            LocalDateTime expiresAt,
            LocalDateTime leaseExpiresAt,
            CertificateEnvelope payload,
            CertificateExecution execution) {
        this(
                id,
                userId,
                sessionId,
                organization,
                generation,
                status,
                maskedDestination,
                failureCode,
                submissionId,
                leaseToken,
                leaseGeneration,
                requestStarted,
                cancelRequested,
                expiresAt,
                leaseExpiresAt,
                payload,
                execution,
                "CERTIFICATE_REGISTER",
                null);
    }

    public boolean certificate() {
        return "CERTIFICATE_REGISTER".equals(operationType);
    }

    @Override
    public String toString() {
        return "AdditionalAuthenticationChallenge[id=" + id + ",status=" + status + "]";
    }
}
