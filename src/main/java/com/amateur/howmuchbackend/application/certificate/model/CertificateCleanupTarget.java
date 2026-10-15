package com.amateur.howmuchbackend.application.certificate.model;

import java.util.UUID;

public record CertificateCleanupTarget(
        String state, UUID connectionRef, CertificateEnvelope envelope) {
    public static CertificateCleanupTarget waiting() {
        return new CertificateCleanupTarget("WAIT", null, null);
    }

    public static CertificateCleanupTarget none() {
        return new CertificateCleanupTarget("NONE", null, null);
    }

    @Override
    public String toString() {
        return "CertificateCleanupTarget[state=" + state + "]";
    }
}
