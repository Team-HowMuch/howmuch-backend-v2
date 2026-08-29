package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementShareLinkResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "진행 중 정산 공유 링크")
public record SettlementShareLinkWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime expiresAt
) {
    public static SettlementShareLinkWebResponse from(SettlementShareLinkResult result) {
        return new SettlementShareLinkWebResponse(result.url(), result.expiresAt());
    }
}
