package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.MatchSettlementContactsCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MatchSettlementContactsWebRequest(
        @NotEmpty
        @Size(max = 500)
        @Valid
        @Schema(description = "매칭할 주소록 연락처 목록")
        List<SettlementContactWebRequest> contacts
) {
    public MatchSettlementContactsCommand toCommand() {
        return new MatchSettlementContactsCommand(
                contacts.stream().map(SettlementContactWebRequest::toCommand).toList()
        );
    }
}
