package com.amateur.howmuchbackend.application.port.in.auth.command;

public record LogoutCommand(
        String clientIp,
        String userAgent
) {

    @Override
    public String toString() {
        return "LogoutCommand[clientIp=***, userAgent=***]";
    }
}
