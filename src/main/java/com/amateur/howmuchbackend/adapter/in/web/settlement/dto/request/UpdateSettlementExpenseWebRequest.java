package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementExpenseCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateSettlementExpenseWebRequest(
        @Size(
                max = SettlementExpense.MAX_DESCRIPTION_LENGTH,
                message = "정산 지출 설명은 500자 이하여야 합니다."
        )
        @Schema(description = "변경할 수동 지출 설명", example = "저녁 식사와 음료", nullable = true)
        String description,

        @Positive(message = "정산 지출 금액은 1원 이상이어야 합니다.")
        @Schema(description = "변경할 수동 지출 금액(원)", example = "48000", nullable = true)
        Long amount
) {
    public UpdateSettlementExpenseCommand toCommand() {
        return new UpdateSettlementExpenseCommand(
                description == null ? null : description.strip(),
                amount
        );
    }

    @AssertTrue(message = "수정할 정산 지출 설명 또는 금액이 필요합니다.")
    @Schema(hidden = true)
    public boolean isUpdateRequested() {
        return description != null || amount != null;
    }

    @AssertTrue(message = "정산 지출 설명은 공백일 수 없습니다.")
    @Schema(hidden = true)
    public boolean isDescriptionValid() {
        return description == null || !description.isBlank();
    }
}
