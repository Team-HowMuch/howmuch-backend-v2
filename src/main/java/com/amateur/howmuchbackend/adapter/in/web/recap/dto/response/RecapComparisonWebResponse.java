package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapComparisonResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record RecapComparisonWebResponse(
        @Schema(allowableValues = {"NO_PREVIOUS", "PREVIOUS_ZERO", "UNCHANGED", "INCREASED", "DECREASED"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "직전 달 저장 리캡의 본인 부담액, 없으면 null", nullable = true) Long previousOwnAmount,
        @Schema(description = "이번 달 - 직전 달, 전월 없으면 null", nullable = true) Long amountDifference,
        @Schema(description = "부호 있는 백분율, HALF_UP 소수점 한 자리. 전월 없음·전월 0원 후 증가이면 null", nullable = true)
        BigDecimal changeRatePercent) {
    public static RecapComparisonWebResponse from(RecapComparisonResult result) {
        return new RecapComparisonWebResponse(result.status(), result.previousOwnAmount(), result.amountDifference(), result.changeRatePercent());
    }
}
