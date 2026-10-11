package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import com.amateur.howmuchbackend.application.port.in.certificate.command.ReserveCertificateClaimCommand;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record ReserveCertificateClaimWebRequest(
        @NotNull UUID sessionId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String qrToken,
        @NotNull UUID idempotencyKey) {
    public ReserveCertificateClaimCommand toCommand() {
        return new ReserveCertificateClaimCommand(sessionId, qrToken, idempotencyKey);
    }

    @Override
    public String toString() {
        return "ReserveCertificateClaimWebRequest[redacted]";
    }
}
