package com.amateur.howmuchbackend.application.port.in.auth.request;

public record LoginRequest(
        String phoneNumber,
        String pin
) {
}
