package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SaveSettlementItemSelectionWebRequest(
        @NotNull
        @Schema(description = "품목 부담 비율", example = "HALF")
        SettlementItemAllocationFraction fraction
) {
    public SaveSettlementItemSelectionCommand toCommand() {
        return new SaveSettlementItemSelectionCommand(fraction);
    }
}
