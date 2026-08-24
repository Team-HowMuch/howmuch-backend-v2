package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import io.swagger.v3.oas.annotations.media.Schema;

public record SettlementListWebRequest(
        @Schema(description = "조회 탭", example = "RECEIVE", defaultValue = "RECEIVE")
        SettlementListTab tab,
        @Schema(description = "상태 필터", example = "IN_PROGRESS", defaultValue = "IN_PROGRESS")
        SettlementListStatusFilter status
) {
    public GetSettlementsCommand toCommand() {
        return new GetSettlementsCommand(tab, status);
    }
}
