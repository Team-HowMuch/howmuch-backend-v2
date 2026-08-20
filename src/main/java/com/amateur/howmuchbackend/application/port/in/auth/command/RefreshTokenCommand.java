package com.amateur.howmuchbackend.application.port.in.auth.command;

public record RefreshTokenCommand(
        String requestToken
) {
}
