package com.amateur.howmuchbackend.application.external.model;

public record AdditionalAuthenticationRequest(
        byte[] payload,
        String maskedDestination,
        int timeoutSeconds,
        java.time.LocalDateTime providerExpiresAt) {
    public AdditionalAuthenticationRequest {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public String toString() {
        return "AdditionalAuthenticationRequest[redacted]";
    }
}
