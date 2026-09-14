package com.amateur.howmuchbackend.adapter.in.web.user.dto.request;

import com.amateur.howmuchbackend.application.port.in.user.command.DeleteMyAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record DeleteMyAccountWebRequest(
        @Schema(description = "현재 계정의 6자리 PIN", example = "123456")
        @NotBlank
        String pin
) {
    public DeleteMyAccountCommand toCommand() {
        return new DeleteMyAccountCommand(pin);
    }
}
