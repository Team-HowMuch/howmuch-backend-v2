package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record StartCertificateReferenceWebRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{4}$") String sourceOrganization,
        @NotBlank @Pattern(regexp = "^[0-9]{4}$") String organization,
        @Pattern(regexp = "^(?:[0-9]{6}|[0-9]{8})$")
                @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
                String birthDate,
        @Size(max = 100) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String accountNumber,
        @Size(max = 100) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
                String accountPassword) {
    public StartCertificateReferenceConnectionCommand toCommand(
            long connectionId, UUID idempotencyKey) {
        return new StartCertificateReferenceConnectionCommand(
                connectionId,
                idempotencyKey,
                sourceOrganization,
                organization,
                birthDate,
                accountNumber,
                accountPassword);
    }

    @Override
    public String toString() {
        return "StartCertificateReferenceWebRequest[sourceOrganization="
                + sourceOrganization
                + ", organization="
                + organization
                + ", credentials=REDACTED]";
    }
}
