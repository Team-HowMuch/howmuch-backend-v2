package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"code", "name", "description", "icon"})
public record SettlementCategoryWebResponse(
        @Schema(description = "카테고리 코드", example = "CAFE_SNACK")
        String code,
        @Schema(description = "표시명", example = "카페·간식")
        String name,
        @Schema(nullable = true, description = "세부 예시", example = "커피, 디저트, 베이커리")
        String description,
        @Schema(description = "아이콘 키. 앱이 아이콘 이미지에 연결한다.", example = "cafe-snack")
        String icon
) {
    public static SettlementCategoryWebResponse from(SettlementCategoryResult result) {
        return new SettlementCategoryWebResponse(result.code(), result.name(), result.description(), result.icon());
    }
}
