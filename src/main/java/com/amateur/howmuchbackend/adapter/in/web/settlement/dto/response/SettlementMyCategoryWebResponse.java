package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "조회하는 회원 기준 카테고리. 회원이 고른 값, 없으면 자동 분류 값이다. "
                + "UNCATEGORIZED면 미분류라 앱이 카테고리 지정을 안내한다.",
        requiredProperties = {"code", "name", "icon"}
)
public record SettlementMyCategoryWebResponse(
        @Schema(description = "카테고리 코드", example = "CAFE_SNACK")
        String code,
        @Schema(description = "표시명", example = "카페·간식")
        String name,
        @Schema(description = "아이콘 키", example = "cafe-snack")
        String icon
) {
    public static SettlementMyCategoryWebResponse from(SettlementCategoryResult result) {
        return result == null ? null : new SettlementMyCategoryWebResponse(result.code(), result.name(), result.icon());
    }
}
