package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementExpenseCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateSettlementExpenseWebRequest(
        @NotBlank(message = "정산 지출 설명은 필수입니다.")
        @Size(
                max = SettlementExpense.MAX_DESCRIPTION_LENGTH,
                message = "정산 지출 설명은 500자 이하여야 합니다."
        )
        @Schema(description = "수동 지출 설명", example = "저녁 식사")
        String description,

        @NotNull(message = "정산 지출 금액은 필수입니다.")
        @Positive(message = "정산 지출 금액은 1원 이상이어야 합니다.")
        @Schema(description = "수동 지출 금액(원)", example = "42000")
        Long amount
) {
    public CreateSettlementExpenseCommand toCommand() {
        return new CreateSettlementExpenseCommand(description.strip(), amount);
    }
}
