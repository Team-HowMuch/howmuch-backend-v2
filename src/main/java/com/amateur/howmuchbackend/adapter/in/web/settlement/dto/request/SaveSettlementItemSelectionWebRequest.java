package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SaveSettlementItemSelectionWebRequest(
        @NotNull
        @Schema(description = "품목 **한 개**에 대한 부담 비율", example = "ONE")
        SettlementItemAllocationFraction fraction,

        @Positive
        @Schema(
                description = "그 비율을 몇 번 가져갈지. 생략하면 1이다. "
                        + "예: 2개는 fraction=ONE·units=2, 1/3개는 fraction=THIRD·units=1, "
                        + "2/3개는 fraction=THIRD·units=2",
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
