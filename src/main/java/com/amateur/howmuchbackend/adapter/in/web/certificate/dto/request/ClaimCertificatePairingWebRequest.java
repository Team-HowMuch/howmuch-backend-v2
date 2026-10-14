package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import com.amateur.howmuchbackend.application.port.in.certificate.command.PairCertificateDeviceCommand;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record ClaimCertificatePairingWebRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String qrToken,
        @NotBlank @Pattern(regexp = "[0-9]{4}") String organization) {
    public PairCertificateDeviceCommand toCommand(UUID id) {
        return new PairCertificateDeviceCommand(id, qrToken, organization);
    }

    @Override
    public String toString() {
        return "ClaimCertificatePairingWebRequest[redacted]";
    }
}
