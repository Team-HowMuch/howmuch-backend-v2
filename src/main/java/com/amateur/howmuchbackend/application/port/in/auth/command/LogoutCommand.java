package com.amateur.howmuchbackend.application.port.in.auth.command;

public record LogoutCommand(
        String clientIp,
        String userAgent
) {
}
