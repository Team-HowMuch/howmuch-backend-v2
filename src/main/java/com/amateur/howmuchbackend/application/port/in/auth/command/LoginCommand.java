package com.amateur.howmuchbackend.application.port.in.auth.command;

public record LoginCommand(
        String phoneNumber,
        String pin
) {

    @Override
    public String toString() {
        return "LoginCommand[phoneNumber=***, pin=***]";
    }
}
