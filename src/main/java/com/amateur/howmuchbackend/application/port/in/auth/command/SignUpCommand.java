package com.amateur.howmuchbackend.application.port.in.auth.command;

public record SignUpCommand(
        String challengeId,
        String name,
        String pin,
        String pinConfirmation,
        boolean requiredTermsAgreed,
        boolean marketingAgreed
) {

    @Override
    public String toString() {
        return "SignUpCommand[challengeId=***, name=***, pin=***, pinConfirmation=***, requiredTermsAgreed=%s, marketingAgreed=%s]"
                .formatted(requiredTermsAgreed, marketingAgreed);
    }
}
