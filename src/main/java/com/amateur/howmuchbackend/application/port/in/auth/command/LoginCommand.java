package com.amateur.howmuchbackend.application.port.in.auth.command;

public record LoginCommand(
        String phoneNumber,
        String pin
) {
}
