package com.amateur.howmuchbackend.domain.account;

public enum CertificateReferenceStatus {
    QUEUED,
    RUNNING,
    CONNECTED,
    FAILED,
    ACTION_REQUIRED,
    UNKNOWN,
    EXPIRED;

    public boolean terminal() {
        return this == CONNECTED || this == FAILED || this == EXPIRED;
    }
}
