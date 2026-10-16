package com.amateur.howmuchbackend.application.port.in.account.result;

import java.util.List;

public record CertificateReferencesResult(
        String organization,
        int authenticationLevel,
        boolean birthDateRequired,
        Long connectionId,
        boolean alreadyConnected,
        List<Reference> references,
        CertificateReferenceOperationResult activeOperation) {
    public record Reference(String sourceOrganization, String name, String iconUrl) {}
}
