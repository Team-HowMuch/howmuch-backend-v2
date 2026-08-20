package com.amateur.howmuchbackend.application.port.in.auth.command;

public record SendSmsVerificationCommand(
        String phoneNumber
) {
}
