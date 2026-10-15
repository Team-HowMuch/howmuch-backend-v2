package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import com.amateur.howmuchbackend.application.port.in.certificate.command.DecideCertificateConnectionCommand;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DecideCertificateConnectionWebRequest(@NotNull Boolean approved) {
    public DecideCertificateConnectionCommand toCommand(UUID sessionId, UUID claimId) {
        return new DecideCertificateConnectionCommand(sessionId, claimId, approved);
    }
}
