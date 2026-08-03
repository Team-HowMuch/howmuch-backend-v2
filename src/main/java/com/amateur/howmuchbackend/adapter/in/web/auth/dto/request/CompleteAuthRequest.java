package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CompleteAuthRequest(
        @NotBlank(message = "인증 challenge ID는 필수입니다.")
        String challengeId,
        @NotBlank(message = "PIN은 필수입니다.")
        @Pattern(regexp = "^\\d{6}$", message = "PIN은 6자리 숫자여야 합니다.")
        String pin
) {
    public com.amateur.howmuchbackend.application.port.in.auth.request.CompleteAuthRequest toRequest() {
        return new com.amateur.howmuchbackend.application.port.in.auth.request.CompleteAuthRequest(
                challengeId,
                pin
        );
    }
}
