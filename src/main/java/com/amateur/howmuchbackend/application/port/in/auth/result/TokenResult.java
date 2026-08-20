package com.amateur.howmuchbackend.application.port.in.auth.result;

public record TokenResult(
        String accessToken,
        String requestToken
) {
}
