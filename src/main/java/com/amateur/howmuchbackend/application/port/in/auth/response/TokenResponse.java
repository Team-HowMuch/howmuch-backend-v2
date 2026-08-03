package com.amateur.howmuchbackend.application.port.in.auth.response;

public record TokenResponse(
        String accessToken,
        String requestToken
) {
}
