package com.amateur.howmuchbackend.application.certificate.model;

public record CertificateConnectionSecret(
        String connectedId, String accountNumber, String accountPassword, String birthDate) {
    @Override
    public String toString() {
        return "CertificateConnectionSecret[REDACTED]";
    }
}
