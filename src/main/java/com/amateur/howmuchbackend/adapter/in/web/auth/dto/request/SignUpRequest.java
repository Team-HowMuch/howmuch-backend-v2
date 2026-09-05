package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank(message = "인증 challenge ID는 필수입니다.")
        String challengeId,
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 100, message = "이름은 100자 이하여야 합니다.")
        String name,
        @NotBlank(message = "PIN은 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "PIN은 6자리 숫자여야 합니다.")
        String pin,
        @NotBlank(message = "PIN 확인 값은 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "PIN 확인 값은 6자리 숫자여야 합니다.")
        String pinConfirmation,
        boolean requiredTermsAgreed,
        boolean marketingAgreed
) {
    public com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand toCommand() {
        return new com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand(
                challengeId,
                name,
                pin,
                pinConfirmation,
                requiredTermsAgreed,
                marketingAgreed
        );
    }

    @Override
    public String toString() {
        return "SignUpRequest[challengeId=***, name=***, pin=***, pinConfirmation=***, requiredTermsAgreed=%s, marketingAgreed=%s]"
                .formatted(requiredTermsAgreed, marketingAgreed);
    }
}
