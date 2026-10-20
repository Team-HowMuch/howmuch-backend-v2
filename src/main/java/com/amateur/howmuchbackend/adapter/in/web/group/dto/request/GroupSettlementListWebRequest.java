package com.amateur.howmuchbackend.adapter.in.web.group.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetGroupSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import io.swagger.v3.oas.annotations.media.Schema;

public record GroupSettlementListWebRequest(
        @Schema(description = "기존 정산 목록과 같은 상태 필터. DRAFT는 항상 제외합니다.",
                defaultValue = "IN_PROGRESS")
        SettlementListStatusFilter status
) {
    public GetGroupSettlementsCommand toCommand(Long groupId) {
        return new GetGroupSettlementsCommand(groupId, status);
    }
}
