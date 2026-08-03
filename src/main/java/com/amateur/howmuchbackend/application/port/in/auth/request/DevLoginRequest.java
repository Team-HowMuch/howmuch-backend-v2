package com.amateur.howmuchbackend.application.port.in.auth.request;

public record DevLoginRequest(
        String name,
        String residentNumber,
        String phoneNumber,
        String pin
) {
}
