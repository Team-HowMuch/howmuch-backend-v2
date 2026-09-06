package com.amateur.howmuchbackend.application.port.in.auth.command;

public record RefreshTokenCommand(
        String requestToken
) {

    @Override
    public String toString() {
        return "RefreshTokenCommand[requestToken=***]";
    }
}
