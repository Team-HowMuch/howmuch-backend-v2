package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmSmsVerificationRequest(
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        String phoneNumber,
        @NotBlank(message = "인증 요청 ID는 필수입니다.")
        String verificationRequestId,
        @NotBlank(message = "인증번호는 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "인증번호는 6자리 숫자여야 합니다.")
        String verificationCode
) {
    public com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand toCommand() {
        return new com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand(
                phoneNumber,
                verificationRequestId,
                verificationCode
        );
    }

    @Override
    public String toString() {
        return "ConfirmSmsVerificationRequest[phoneNumber=***, verificationRequestId=***, verificationCode=***]";
    }
}
