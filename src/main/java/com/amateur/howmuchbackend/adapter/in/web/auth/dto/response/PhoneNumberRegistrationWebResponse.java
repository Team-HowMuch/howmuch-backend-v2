package com.amateur.howmuchbackend.adapter.in.web.auth.dto.response;

import com.amateur.howmuchbackend.application.port.in.auth.result.PhoneNumberRegistrationResult;

public record PhoneNumberRegistrationWebResponse(
        boolean registered
) {
    public static PhoneNumberRegistrationWebResponse from(PhoneNumberRegistrationResult result) {
        return new PhoneNumberRegistrationWebResponse(result.registered());
    }
}
