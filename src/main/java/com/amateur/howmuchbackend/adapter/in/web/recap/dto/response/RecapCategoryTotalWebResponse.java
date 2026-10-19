package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapCategoryTotalResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"rank", "category", "totalOwnAmount"})
public record RecapCategoryTotalWebResponse(
        @Schema(description = "본인 부담액 합계 내림차순, 동률은 카테고리 코드순", minimum = "1", maximum = "3") int rank,
        RecapCategoryWebResponse category,
        @Schema(description = "해당 카테고리 정산의 본인 부담액 합계, 원", minimum = "0") long totalOwnAmount) {
    public static RecapCategoryTotalWebResponse from(RecapCategoryTotalResult total) {
        return new RecapCategoryTotalWebResponse(total.rank(), RecapCategoryWebResponse.from(total.category()), total.totalOwnAmount());
    }
}
