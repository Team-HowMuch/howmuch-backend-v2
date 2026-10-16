package com.amateur.howmuchbackend.application.account.model;

public final class CertificateReferenceUncertainException extends RuntimeException {
    public CertificateReferenceUncertainException() {
        super("Reference connection outcome requires reconciliation");
    }
}
