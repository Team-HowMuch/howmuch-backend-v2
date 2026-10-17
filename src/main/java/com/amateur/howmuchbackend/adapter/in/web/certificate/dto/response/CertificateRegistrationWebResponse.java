package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateRegistrationResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record CertificateRegistrationWebResponse(
        UUID id,
        String organization,
        String status,
        LocalDateTime expiresAt,
        String pcCapability,
        String uploadCapability,
        String failureCode,
        String failureMessage,
        com.amateur.howmuchbackend.adapter.in.web.external.dto.response
                        .AdditionalAuthenticationWebResponse
                additionalAuthentication) {
    public static CertificateRegistrationWebResponse from(CertificateRegistrationResult r) {
        return new CertificateRegistrationWebResponse(
                r.id(),
                r.organization(),
                r.status(),
                r.expiresAt(),
                r.pcCapability(),
                r.uploadCapability(),
                r.failureCode(),
                r.failureMessage(),
                com.amateur.howmuchbackend.adapter.in.web.external.dto.response
                        .AdditionalAuthenticationWebResponse.from(r.additionalAuthentication()));
    }

    @Override
    public String toString() {
        return "CertificateRegistrationWebResponse[id=" + id + ",status=" + status + "]";
    }
}
