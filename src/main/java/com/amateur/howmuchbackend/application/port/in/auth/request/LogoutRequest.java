package com.amateur.howmuchbackend.application.port.in.auth.request;

public record LogoutRequest(
        String clientIp,
        String userAgent
) {
}
