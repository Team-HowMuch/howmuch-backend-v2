package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateSettlementWebRequest(
        @NotBlank(message = "정산 제목은 필수입니다.")
        @Size(max = Settlement.MAX_TITLE_LENGTH, message = "정산 제목은 100자 이하여야 합니다.")
        @Schema(description = "정산 제목", example = "8월 모임 정산")
        String title,

        @NotNull(message = "정산 총액은 필수입니다.")
        @Positive(message = "정산 총액은 1원 이상이어야 합니다.")
        @Schema(description = "정산 대상 총액(원)", example = "10000")
        Long totalAmount
) {
    public CreateSettlementCommand toCommand() {
        return new CreateSettlementCommand(title.trim(), totalAmount);
    }
}
