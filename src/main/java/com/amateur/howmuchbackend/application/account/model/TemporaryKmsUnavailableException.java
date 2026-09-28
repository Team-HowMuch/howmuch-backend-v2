package com.amateur.howmuchbackend.application.account.model;

public class TemporaryKmsUnavailableException extends RuntimeException {
    public TemporaryKmsUnavailableException(Throwable cause) {
        super("KMS is temporarily unavailable.", cause);
    }
}
