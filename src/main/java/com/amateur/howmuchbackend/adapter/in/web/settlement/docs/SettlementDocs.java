package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "정산 API", description = "정산 초안 생성·조회·수정 API")
public interface SettlementDocs {
    @Operation(
            summary = "정산 초안 생성",
            description = "현재 로그인한 사용자를 생성자로 하는 정산 초안을 생성합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementWebResponse> createSettlement(CreateSettlementWebRequest request);
}
