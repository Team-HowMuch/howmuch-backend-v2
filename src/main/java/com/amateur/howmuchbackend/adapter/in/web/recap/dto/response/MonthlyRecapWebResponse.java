package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.MonthlyRecapResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record MonthlyRecapWebResponse(
        @Schema(example = "2026-09", requiredMode = Schema.RequiredMode.REQUIRED) String yearMonth,
        @Schema(description = "생성 당시 본인 이름", requiredMode = Schema.RequiredMode.REQUIRED) String ownerName,
        @Schema(description = "UTC 생성 시각", requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completedSettlementCount,
        @Schema(description = "본인의 확정 Share 부담액 합계, 원", requiredMode = Schema.RequiredMode.REQUIRED) long totalOwnAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecapComparisonWebResponse comparison,
        @Schema(description = "선정된 동행자, 없으면 null", nullable = true) RecapCompanionWebResponse companion) {
    public static MonthlyRecapWebResponse from(MonthlyRecapResult result) {
        String month = result.yearMonth().toString();
        return new MonthlyRecapWebResponse(month, result.ownerName(), result.generatedAt(), result.completedSettlementCount(),
                result.totalOwnAmount(), RecapComparisonWebResponse.from(result.comparison()), RecapCompanionWebResponse.from(result.companion(), month));
    }
}
