package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.group.command.TransferGroupOwnershipCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferGroupOwnershipWebRequest(
        @NotNull(message = "소유권 이전 대상 멤버 ID는 필수입니다.")
        @Positive(message = "소유권 이전 대상 멤버 ID는 양수여야 합니다.")
        @Schema(description = "소유권을 이전할 그룹 멤버십 ID", example = "42")
        Long targetMemberId
) {
    public TransferGroupOwnershipCommand toCommand() {
        return new TransferGroupOwnershipCommand(targetMemberId);
    }
}
