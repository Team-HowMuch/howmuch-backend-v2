package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapCategoryResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "리캡 생성 당시 고정한 카테고리 표시값", requiredProperties = {"code", "name", "icon"})
public record RecapCategoryWebResponse(
        @Schema(description = "카테고리 코드", example = "CAFE_SNACK") String code,
        @Schema(description = "표시명", example = "카페 / 간식") String name,
        @Schema(description = "아이콘 키", example = "cafe-snack") String icon) {
    public static RecapCategoryWebResponse from(RecapCategoryResult category) {
        return category == null ? null : new RecapCategoryWebResponse(category.code(), category.name(), category.icon());
    }
}
