package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateSettlementDraftWebRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "정산 제목은 공백일 수 없습니다.")
        @Size(max = Settlement.MAX_TITLE_LENGTH, message = "정산 제목은 100자 이하여야 합니다.")
        @Schema(description = "변경할 정산 제목", example = "8월 모임 최종 정산", nullable = true)
        String title,

        @Positive(message = "정산 총액은 1원 이상이어야 합니다.")
        @Schema(description = "변경할 정산 대상 총액(원)", example = "12000", nullable = true)
        Long totalAmount
) {
    public UpdateSettlementDraftCommand toCommand() {
        return new UpdateSettlementDraftCommand(
                title == null ? null : title.trim(),
                totalAmount
        );
    }

    @AssertTrue(message = "수정할 제목 또는 총액이 필요합니다.")
    @Schema(hidden = true)
    public boolean isUpdateRequested() {
        return title != null || totalAmount != null;
    }
}
