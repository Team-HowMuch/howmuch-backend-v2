package com.amateur.howmuchbackend.application.certificate.model;

public record CertificateProviderResult(String connectedId, String failureCode) {
    public static CertificateProviderResult connected(String id) {
        return new CertificateProviderResult(id, null);
    }

    public static CertificateProviderResult rejected(String code) {
        return new CertificateProviderResult(null, code);
    }

    @Override
    public String toString() {
        return "CertificateProviderResult[redacted]";
    }
}
