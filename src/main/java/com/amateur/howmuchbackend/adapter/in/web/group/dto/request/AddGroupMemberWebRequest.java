package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.AddGroupMemberCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record AddGroupMemberWebRequest(
        @NotBlank(message = "사용자 공개 ID는 필수입니다.")
        @Schema(description = "추가할 사용자의 공개 ID", example = "sua.choi")
        String publicId
) {
    public AddGroupMemberCommand toCommand() {
        return new AddGroupMemberCommand(publicId);
    }
}
