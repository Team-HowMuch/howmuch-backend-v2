package com.amateur.howmuchbackend.application.port.in.certificate.result;

public record CertificateInstitutionResult(
        String organization,
        String name,
        boolean supported,
        boolean registrationAccountRequired,
        boolean accountListAccountRequired,
        boolean birthDateRequired,
        String reference,
        int authenticationLevel) {
    public CertificateInstitutionResult(
            String organization,
            String name,
            boolean supported,
            boolean registrationAccountRequired,
            boolean accountListAccountRequired,
            boolean birthDateRequired,
            String reference
    ) {
        this(
                organization, name, supported, registrationAccountRequired,
                accountListAccountRequired, birthDateRequired, reference, 1
        );
    }

    public boolean requiresAccountCredentials() {
        return authenticationLevel >= 3
                || registrationAccountRequired
                || accountListAccountRequired;
    }
}
