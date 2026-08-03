package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DevLoginRequest(
        @NotBlank(message = "이름은 필수입니다.")
        String name,
        @NotBlank(message = "주민등록번호 앞 7자리는 필수입니다.")
        @Pattern(regexp = "^\\d{7}$", message = "주민등록번호 앞 7자리는 7자리 숫자여야 합니다.")
        String residentNumber,
        @NotBlank(message = "휴대폰 번호는 필수입니다.")
        String phoneNumber,
        @NotBlank(message = "PIN은 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "PIN은 6자리 숫자여야 합니다.")
        String pin
) {
    public com.amateur.howmuchbackend.application.port.in.auth.request.DevLoginRequest toRequest() {
        return new com.amateur.howmuchbackend.application.port.in.auth.request.DevLoginRequest(
                name,
                residentNumber,
                phoneNumber,
                pin
        );
    }
}
