package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupInvitationCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateGroupInvitationWebRequest(
        @NotBlank @Schema(description = "초대받을 사용자의 공개 ID", example = "sua.choi") String publicId) {
    public CreateGroupInvitationCommand toCommand() { return new CreateGroupInvitationCommand(publicId); }
}
