package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import com.amateur.howmuchbackend.application.port.in.auth.command.PhoneNumberRegistrationCheckCommand;

public record PhoneNumberRegistrationCheckRequest(
        String phoneNumber
) {
    public PhoneNumberRegistrationCheckCommand toCommand(String clientAddress) {
        return new PhoneNumberRegistrationCheckCommand(phoneNumber, clientAddress);
    }

    @Override
    public String toString() {
        return "PhoneNumberRegistrationCheckRequest[phoneNumber=***]";
    }
}
