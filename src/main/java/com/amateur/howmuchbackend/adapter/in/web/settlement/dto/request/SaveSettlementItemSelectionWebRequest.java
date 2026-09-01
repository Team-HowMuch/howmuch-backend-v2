package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SaveSettlementItemSelectionWebRequest(
        @NotNull
        @Schema(description = "품목 부담 비율", example = "HALF")
        SettlementItemAllocationFraction fraction,

        @Positive
        @Schema(
                description = "가져갈 수량. 의미는 units × fraction 이다. "
                        + "생략하면 1로 처리해 기존 클라이언트와 동일하게 동작한다. "
                        + "예: 3인분 중 2인분은 units=2, fraction=THIRD",
                example = "2",
                defaultValue = "1",
                nullable = true
        )
        Integer units
) {
    private static final int DEFAULT_UNITS = 1;

    public SaveSettlementItemSelectionCommand toCommand() {
        return new SaveSettlementItemSelectionCommand(
                fraction,
                units == null ? DEFAULT_UNITS : units
        );
    }
}
