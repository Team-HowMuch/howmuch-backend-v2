package com.amateur.howmuchbackend.application.port.in.certificate.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateRegistrationResult(
        UUID id,
        String organization,
        String status,
        LocalDateTime expiresAt,
        String pcCapability,
        String uploadCapability,
        String failureCode,
        String failureMessage,
        com.amateur.howmuchbackend.application.port.in.external.result
                        .AdditionalAuthenticationResult
                additionalAuthentication) {
    public CertificateRegistrationResult(
            UUID id,
            String organization,
            String status,
            LocalDateTime expiresAt,
            String pcCapability,
            String uploadCapability,
            String failureCode,
            String failureMessage) {
        this(
                id,
                organization,
                status,
                expiresAt,
                pcCapability,
                uploadCapability,
                failureCode,
                failureMessage,
                null);
    }

    @Override
    public String toString() {
        return "CertificateRegistrationResult[id=" + id + ",status=" + status + "]";
    }
}
