package com.amateur.howmuchbackend.application.port.in.auth.command;

import java.time.LocalDate;

public record SignUpCommand(
        String challengeId,
        String name,
        LocalDate birthDate,
        String pin,
        String pinConfirmation,
        boolean requiredTermsAgreed,
        boolean marketingAgreed
) {

    @Override
    public String toString() {
        return "SignUpCommand[challengeId=***, name=***, birthDate=***, pin=***, pinConfirmation=***, requiredTermsAgreed=%s, marketingAgreed=%s]"
                .formatted(requiredTermsAgreed, marketingAgreed);
    }
}
