package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SearchSettlementParticipantCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchSettlementParticipantWebRequest(
        @NotBlank
        @Size(max = 20)
        @Schema(description = "검색할 공개 ID prefix", example = "sua")
        String publicIdPrefix
) {
    public SearchSettlementParticipantCommand toCommand() {
        return new SearchSettlementParticipantCommand(publicIdPrefix);
    }
}
