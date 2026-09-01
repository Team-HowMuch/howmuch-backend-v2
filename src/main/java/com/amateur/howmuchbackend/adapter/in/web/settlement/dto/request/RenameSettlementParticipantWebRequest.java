package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameSettlementParticipantWebRequest(
        @NotBlank(message = "참여자 표시 이름은 필수입니다.")
        @Size(
                max = SettlementParticipant.MAX_DISPLAY_NAME_LENGTH,
                message = "참여자 표시 이름은 100자 이하여야 합니다."
        )
        @Schema(
                description = "이 정산에서 참여자를 부를 이름",
                example = "민선영"
        )
        String displayName
) {
}
