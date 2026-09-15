package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import com.amateur.howmuchbackend.application.port.in.auth.command.PhoneNumberRegistrationCheckCommand;
import io.swagger.v3.oas.annotations.media.Schema;

public record PhoneNumberRegistrationCheckRequest(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "가입 여부를 확인할 휴대폰 번호",
                example = "01012345678"
        )
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
