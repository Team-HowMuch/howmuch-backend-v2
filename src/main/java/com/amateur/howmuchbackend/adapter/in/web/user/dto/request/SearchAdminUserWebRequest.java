package com.amateur.howmuchbackend.adapter.in.web.user.dto.request;

import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchAdminUserWebRequest(
        @NotBlank
        @Size(max = 32)
        @Schema(description = "삭제 요청자가 제공한 휴대폰 번호", accessMode = Schema.AccessMode.WRITE_ONLY)
        String phoneNumber
) {
    public SearchAdminUserCommand toCommand() {
        return new SearchAdminUserCommand(phoneNumber);
    }

    @Override
    public String toString() {
        return "SearchAdminUserWebRequest[phoneNumber=***]";
    }
}
