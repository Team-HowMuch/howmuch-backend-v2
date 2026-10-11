package com.amateur.howmuchbackend.application.certificate.model;

public record CertificateEnvelope(byte[] payload, byte[] wrappedKey, String keyId) {
    public CertificateEnvelope {
        payload = payload.clone();
        wrappedKey = wrappedKey.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public byte[] wrappedKey() {
        return wrappedKey.clone();
    }

    @Override
    public String toString() {
        return "CertificateEnvelope[REDACTED]";
    }
}
