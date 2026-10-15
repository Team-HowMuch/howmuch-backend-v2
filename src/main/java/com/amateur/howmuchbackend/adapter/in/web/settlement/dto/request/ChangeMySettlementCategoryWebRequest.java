package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ChangeMySettlementCategoryCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeMySettlementCategoryWebRequest(
        @NotBlank
        @Size(max = 50)
        @Schema(
                description = "카테고리 목록 API(GET /api/v2/settlement-categories)의 code. 미분류(UNCATEGORIZED)도 고를 수 있다",
                example = "FOOD",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String categoryCode
) {
    public ChangeMySettlementCategoryCommand toCommand() {
        return new ChangeMySettlementCategoryCommand(categoryCode);
    }
}
