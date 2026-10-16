package com.amateur.howmuchbackend.application.account.model;

public record CertificateReferenceSecret(
        String connectedId, String birthDate, String accountNumber, String accountPassword) {
    @Override
    public String toString() {
        return "CertificateReferenceSecret[REDACTED]";
    }
}
