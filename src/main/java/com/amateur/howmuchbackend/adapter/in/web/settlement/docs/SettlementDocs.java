package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 API", description = "정산 초안 생성·조회·수정 API")
public interface SettlementDocs {
    @Operation(
            summary = "정산 초안 생성",
            description = "현재 로그인한 사용자를 생성자로 하는 정산 초안을 생성합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementWebResponse> createSettlement(CreateSettlementWebRequest request);

    @Operation(
            summary = "정산 초안 조회",
            description = "현재 로그인한 사용자가 생성한 정산을 ID로 조회합니다. 다른 생성자의 정산은 조회할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementWebResponse> getSettlement(UUID settlementId);

    @Operation(
            summary = "정산 초안 수정",
            description = "현재 로그인한 생성자가 DRAFT 상태 정산의 제목 또는 총액을 수정합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementWebResponse> updateSettlement(
            UUID settlementId,
            UpdateSettlementDraftWebRequest request
    );
}
