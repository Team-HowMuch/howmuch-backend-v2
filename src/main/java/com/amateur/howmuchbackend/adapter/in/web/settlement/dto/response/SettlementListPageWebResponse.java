package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListPageResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SettlementListPageWebResponse(
        @Schema(description = "현재 페이지 정산 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SettlementListItemWebResponse> items,
        @Schema(description = "다음 페이지 커서. 마지막·빈 페이지는 null", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String nextCursor,
        @Schema(description = "다음 페이지 존재 여부", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasNext
) {
    public static SettlementListPageWebResponse from(SettlementListPageResult result) {
        return new SettlementListPageWebResponse(
                result.items().stream().map(SettlementListItemWebResponse::from).toList(),
                result.nextCursor(), result.hasNext()
        );
    }
}
