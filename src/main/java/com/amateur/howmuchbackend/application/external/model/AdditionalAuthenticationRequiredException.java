package com.amateur.howmuchbackend.application.external.model;

public final class AdditionalAuthenticationRequiredException extends RuntimeException {
    private final AdditionalAuthenticationRequest request;

    public AdditionalAuthenticationRequiredException(AdditionalAuthenticationRequest request) {
        super("SMS continuation required");
        this.request = request;
    }

    public AdditionalAuthenticationRequest request() {
        return request;
    }
}
