package com.amateur.howmuchbackend.application.port.in.auth.request;

public record CompleteAuthRequest(
        String challengeId,
        String pin
) {
}
