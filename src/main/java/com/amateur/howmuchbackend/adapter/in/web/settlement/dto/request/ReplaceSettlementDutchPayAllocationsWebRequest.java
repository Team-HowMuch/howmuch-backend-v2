package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.UUID;

public record ReplaceSettlementDutchPayAllocationsWebRequest(
        @NotEmpty
        @Schema(description = "정산 참여자 전원의 부담 금액")
        List<@NotNull @Valid Allocation> allocations
) {
    public ReplaceSettlementDutchPayAllocationsCommand toCommand() {
        return new ReplaceSettlementDutchPayAllocationsCommand(
                allocations.stream()
                        .map(allocation -> new ReplaceSettlementDutchPayAllocationsCommand.Allocation(
                                allocation.participantId(),
                                allocation.amount()
                        ))
                        .toList()
        );
    }

    public record Allocation(
            @NotNull
            @Schema(description = "정산 참여자 ID")
            UUID participantId,

            @NotNull
            @PositiveOrZero
            @Schema(description = "참여자의 더치페이 부담 금액", example = "13334")
            Long amount
    ) {
    }
}
