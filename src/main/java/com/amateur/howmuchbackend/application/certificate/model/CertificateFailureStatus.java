package com.amateur.howmuchbackend.application.certificate.model;

import static com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode.*;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import java.util.Arrays;

public final class CertificateFailureStatus {
    private CertificateFailureStatus() {}

    public static StatusCode from(String code) {
        if (code == null) return null;
        return switch (code) {
            case "REGISTRATION_REJECTED" -> ExternalConnectionStatusCode.BANK_CONNECTION_FAILED;
            case "REGISTRATION_UNCERTAIN", "BINDING_UNCERTAIN" ->
                    CERTIFICATE_REGISTRATION_UNCERTAIN;
            case "ADDITIONAL_AUTH_REQUIRED" ->
                    ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED;
            case "UPLOAD_EXPIRED" -> CERTIFICATE_EXPIRED;
            case "PROVIDER_UNAVAILABLE" -> ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE;
            case "CONNECTION_CONFLICT", "CONTRACT_CONFLICT" -> CERTIFICATE_CONFLICT;
            case "OWNER_VERIFICATION_REQUIRED" -> CERTIFICATE_OWNER_VERIFICATION_REQUIRED;
            default ->
                    Arrays.stream(ExternalConnectionStatusCode.values())
                            .filter(status -> status.getCode().equals(code))
                            .findFirst()
                            .orElse(ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR);
        };
    }
}
