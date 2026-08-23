package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateSettlementDutchPayAllocationWebRequest(
        @NotNull
        @PositiveOrZero
        @Schema(description = "참여자의 더치페이 부담 금액", example = "4000")
        Long amount
) {
    public UpdateSettlementDutchPayAllocationCommand toCommand() {
        return new UpdateSettlementDutchPayAllocationCommand(amount);
    }
}
