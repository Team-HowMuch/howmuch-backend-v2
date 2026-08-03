package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SendSmsVerificationRequest(
        @NotBlank(message = "이름은 필수입니다.")
        String name,
        @NotBlank(message = "주민등록번호 앞 7자리는 필수입니다.")
        @Pattern(regexp = "^\\d{7}$", message = "주민등록번호 앞 7자리는 7자리 숫자여야 합니다.")
        String residentNumber,
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        String phoneNumber
) {
    public com.amateur.howmuchbackend.application.port.in.auth.request.SendSmsVerificationRequest toRequest() {
        return new com.amateur.howmuchbackend.application.port.in.auth.request.SendSmsVerificationRequest(
                name,
                residentNumber,
                phoneNumber
        );
    }
}
