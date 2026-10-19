package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.MonthlyRecapResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Schema(requiredProperties = {"categories", "record", "benefits"})
public record MonthlyRecapWebResponse(
        @Schema(example = "2026-09", requiredMode = Schema.RequiredMode.REQUIRED) String yearMonth,
        @Schema(description = "생성 당시 본인 이름", requiredMode = Schema.RequiredMode.REQUIRED) String ownerName,
        @Schema(description = "UTC 생성 시각", requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completedSettlementCount,
        @Schema(description = "본인의 확정 Share 부담액 합계, 원", requiredMode = Schema.RequiredMode.REQUIRED) long totalOwnAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecapComparisonWebResponse comparison,
        @Schema(description = "선정된 동행자, 없으면 null", nullable = true) RecapCompanionWebResponse companion,
        @Size(max = 3)
        @Schema(description = "본인 카테고리(수정값 우선)의 부담액 TOP3. 미분류 제외, 자료가 없거나 기존 리캡이면 null", nullable = true) List<RecapCategoryTotalWebResponse> categories,
        @Schema(description = "생성 당시 고정한 네 가지 기록. 기존 리캡은 null이며 각 칸도 자료가 없으면 null", nullable = true) RecapRecordWebResponse record,
        @Size(max = 2)
        @Schema(description = "최대 2개 제휴 혜택. 제휴 데이터가 준비되지 않아 현재 생성하는 리캡은 null", nullable = true) List<RecapBenefitWebResponse> benefits) {
    public static MonthlyRecapWebResponse from(MonthlyRecapResult result) {
        String month = result.yearMonth().toString();
        return new MonthlyRecapWebResponse(month, result.ownerName(), result.generatedAt(), result.completedSettlementCount(),
                result.totalOwnAmount(), RecapComparisonWebResponse.from(result.comparison()), RecapCompanionWebResponse.from(result.companion(), month),
                result.categories() == null ? null : result.categories().stream().map(RecapCategoryTotalWebResponse::from).toList(),
                RecapRecordWebResponse.from(result.record()),
                result.benefits() == null ? null : result.benefits().stream().map(RecapBenefitWebResponse::from).toList());
    }
}
