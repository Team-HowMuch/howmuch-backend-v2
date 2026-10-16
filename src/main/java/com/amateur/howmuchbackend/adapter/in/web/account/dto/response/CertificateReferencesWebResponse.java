package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.CertificateReferencesResult;

import java.util.List;

public record CertificateReferencesWebResponse(
        String organization,
        int authenticationLevel,
        boolean birthDateRequired,
        Long connectionId,
        boolean alreadyConnected,
        List<Reference> references,
        CertificateReferenceOperationWebResponse activeOperation) {
    public record Reference(String sourceOrganization, String name, String iconUrl) {}

    public static CertificateReferencesWebResponse from(CertificateReferencesResult r) {
        return new CertificateReferencesWebResponse(
                r.organization(),
                r.authenticationLevel(),
                r.birthDateRequired(),
                r.connectionId(),
                r.alreadyConnected(),
                r.references().stream()
                        .map(i -> new Reference(i.sourceOrganization(), i.name(), i.iconUrl()))
                        .toList(),
                r.activeOperation() == null
                        ? null
                        : CertificateReferenceOperationWebResponse.from(r.activeOperation()));
    }
}
