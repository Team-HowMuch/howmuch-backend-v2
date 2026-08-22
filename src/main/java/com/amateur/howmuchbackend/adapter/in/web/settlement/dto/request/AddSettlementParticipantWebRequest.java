package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddSettlementParticipantWebRequest(
        @NotNull(message = "참여자 선택 경로는 필수입니다.")
        @Schema(
                description = "참여자 선택 경로",
                allowableValues = {"REGISTERED_USER", "CONTACT"},
                example = "REGISTERED_USER"
        )
        SettlementParticipantSource source,

        @Size(max = 20, message = "사용자 공개 ID는 20자 이하여야 합니다.")
        @Schema(
                description = "등록 사용자 선택 시 사용할 공개 ID",
                example = "sua.choi",
                nullable = true
        )
        String publicId,

        @Size(
                max = SettlementParticipant.MAX_DISPLAY_NAME_LENGTH,
                message = "연락처 표시 이름은 100자 이하여야 합니다."
        )
        @Schema(
                description = "연락처 선택 시 사용할 표시 이름",
                example = "민선녀",
                nullable = true
        )
        String displayName,

        @Size(max = 20, message = "전화번호는 20자 이하여야 합니다.")
        @Schema(
                description = "연락처 선택 시 사용할 휴대폰 번호",
                example = "010-1234-5678",
                nullable = true
        )
        String phoneNumber
) {
    public AddSettlementParticipantCommand toCommand() {
        return new AddSettlementParticipantCommand(source, publicId, displayName, phoneNumber);
    }
}
