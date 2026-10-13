package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.SharedMonthlyRecapResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SharedMonthlyRecapWebResponse(
        @Schema(example = "2026-09", requiredMode = Schema.RequiredMode.REQUIRED) String yearMonth,
        @Schema(description = "UTC 생성 시각", requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completedSettlementCount,
        @Schema(description = "본인 부담액 합계, 원", requiredMode = Schema.RequiredMode.REQUIRED) long totalOwnAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecapComparisonWebResponse comparison,
        @Schema(nullable = true) RecapCompanionWebResponse companion) {
    public static SharedMonthlyRecapWebResponse from(SharedMonthlyRecapResult result) {
        String month = result.yearMonth().toString();
        return new SharedMonthlyRecapWebResponse(month, result.generatedAt(), result.completedSettlementCount(), result.totalOwnAmount(),
                RecapComparisonWebResponse.from(result.comparison()), RecapCompanionWebResponse.from(result.companion(), month));
    }
}
