package com.amateur.howmuchbackend.application.port.in.auth.request;

public record ConfirmSmsVerificationRequest(
        String phoneNumber,
        String verificationRequestId,
        String verificationCode
) {
}
