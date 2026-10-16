package com.amateur.howmuchbackend.application.account.model;

import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.domain.account.CertificateReferenceOperation;

import java.util.UUID;

public record CertificateReferenceExecution(
        CertificateReferenceOperation operation,
        UUID leaseToken,
        long generation,
        CertificateEnvelope envelope) {
    @Override
    public String toString() {
        return "CertificateReferenceExecution[operation=" + operation.id() + ", secrets=REDACTED]";
    }
}
