package com.amateur.howmuchbackend.application.port.in.certificate.result;

public record CertificateInstitutionResult(
        String organization,
        String name,
        boolean supported,
        boolean registrationAccountRequired,
        boolean accountListAccountRequired,
        boolean birthDateRequired,
        String reference) {}
