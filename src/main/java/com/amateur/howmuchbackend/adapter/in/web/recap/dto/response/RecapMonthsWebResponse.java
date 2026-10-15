package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapMonthsResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RecapMonthsWebResponse(
        @Schema(description = "저장된 월, 최신순", example = "[\"2026-09\",\"2026-08\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> months) {
    public static RecapMonthsWebResponse from(RecapMonthsResult result) {
        return new RecapMonthsWebResponse(result.months().stream().map(Object::toString).toList());
    }
}
