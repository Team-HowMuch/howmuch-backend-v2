package com.amateur.howmuchbackend.application.port.in.certificate.result;

public record CertificateInstitutionResult(
        String organization,
        String name,
        boolean supported,
        boolean birthDateRequired,
        String reference,
        int authenticationLevel) {
    public boolean requiresAccountCredentials() {
        return authenticationLevel == 3;
    }
}
