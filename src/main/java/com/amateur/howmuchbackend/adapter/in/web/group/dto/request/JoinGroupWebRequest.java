package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import io.swagger.v3.oas.annotations.media.Schema;

public record JoinGroupWebRequest(
        @Schema(description = "8자리 그룹 초대 코드", example = "AB12CD34")
        String inviteCode
) {
    public JoinGroupCommand toCommand() {
        return new JoinGroupCommand(inviteCode);
    }
}
