package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SendSmsVerificationRequest(
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        String phoneNumber
) {
    public com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand toCommand() {
        return new com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand(phoneNumber);
    }
}
