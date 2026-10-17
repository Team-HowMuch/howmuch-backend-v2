package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

@Tag(name = "반복 정산 홈", description = "현재 사용자가 참여 중인 반복 정산 요약")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공",
            useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요")
})
public interface RecurringSettlementHomeDocs {
    @Operation(summary = "반복 정산 홈 요약",
            description = "현재 사용자가 참여 중인 ACTIVE 반복 정산을 현재 활성 그룹·활성 멤버 조건으로 조회해 "
                    + "도래 임박 순으로 돌려줍니다. limit은 1..20(기본 5)입니다. 다른 사용자의 계좌·설정은 포함하지 않습니다.")
    ApiResponse<List<RecurringSettlementSummaryWebResponse>> home(int limit);
}
