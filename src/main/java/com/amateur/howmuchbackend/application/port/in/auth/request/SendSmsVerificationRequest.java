package com.amateur.howmuchbackend.application.port.in.auth.request;

public record SendSmsVerificationRequest(
        String phoneNumber
) {
}
