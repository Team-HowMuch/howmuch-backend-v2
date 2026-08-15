package com.amateur.howmuchbackend.application.port.in.auth.request;

public record SignUpRequest(
        String challengeId,
        String name,
        String pin,
        String pinConfirmation,
        boolean requiredTermsAgreed,
        boolean marketingAgreed
) {
}
