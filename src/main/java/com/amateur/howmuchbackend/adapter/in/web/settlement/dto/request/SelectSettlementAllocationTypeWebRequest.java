package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementAllocationTypeCommand;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SelectSettlementAllocationTypeWebRequest(
        @NotNull
        @Schema(description = "정산 배분 유형", example = "DUTCH_PAY")
        SettlementAllocationType allocationType
) {
    public SelectSettlementAllocationTypeCommand toCommand() {
        return new SelectSettlementAllocationTypeCommand(allocationType);
    }
}
