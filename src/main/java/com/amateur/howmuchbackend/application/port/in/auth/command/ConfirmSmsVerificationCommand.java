package com.amateur.howmuchbackend.application.port.in.auth.command;

public record ConfirmSmsVerificationCommand(
        String phoneNumber,
        String verificationRequestId,
        String verificationCode
) {

    @Override
    public String toString() {
        return "ConfirmSmsVerificationCommand[phoneNumber=***, verificationRequestId=***, verificationCode=***]";
    }
}
