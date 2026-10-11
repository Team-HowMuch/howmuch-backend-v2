package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;

public record CertificateInstitutionWebResponse(
        String organization,
        String name,
        boolean supported,
        boolean registrationAccountRequired,
        boolean accountListAccountRequired,
        boolean birthDateRequired,
        String reference) {
    public static CertificateInstitutionWebResponse from(CertificateInstitutionResult r) {
        return new CertificateInstitutionWebResponse(
                r.organization(),
                r.name(),
                r.supported(),
                r.registrationAccountRequired(),
                r.accountListAccountRequired(),
                r.birthDateRequired(),
                r.reference());
    }
}
