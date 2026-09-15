package com.amateur.howmuchbackend.application.port.in.auth.command;

public record PhoneNumberRegistrationCheckCommand(
        String phoneNumber,
        String clientAddress
) {
    @Override
    public String toString() {
        return "PhoneNumberRegistrationCheckCommand[phoneNumber=***, clientAddress=***]";
    }
}
