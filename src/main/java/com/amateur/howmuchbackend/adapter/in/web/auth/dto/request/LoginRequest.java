package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        String phoneNumber,
        @NotBlank(message = "PIN은 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "PIN은 6자리 숫자여야 합니다.")
        String pin
) {
    public com.amateur.howmuchbackend.application.port.in.auth.request.LoginRequest toRequest() {
        return new com.amateur.howmuchbackend.application.port.in.auth.request.LoginRequest(phoneNumber, pin);
    }
}
