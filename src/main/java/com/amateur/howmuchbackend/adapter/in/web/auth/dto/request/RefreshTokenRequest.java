package com.amateur.howmuchbackend.adapter.in.web.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message = "requestToken은 필수입니다.")
        String requestToken
) {
    public com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand toCommand() {
        return new com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand(requestToken);
    }
}
