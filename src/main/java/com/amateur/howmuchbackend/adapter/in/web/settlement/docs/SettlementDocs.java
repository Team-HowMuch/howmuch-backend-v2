package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 API", description = "정산 초안 생성·조회·수정 API")
public interface SettlementDocs {
    @Operation(
            summary = "정산 초안 생성",
            description = "현재 로그인한 사용자를 생성자로 하는 정산 초안을 생성합니다. "
                    + "제목 또는 총액이 유효하지 않으면 INVALID_ARGUMENT을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 생성 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 요청값 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            )
    })
    ApiResponse<SettlementWebResponse> createSettlement(CreateSettlementWebRequest request);

    @Operation(
            summary = "정산 초안 조회",
            description = "현재 로그인한 사용자가 생성한 정산을 ID로 조회합니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            )
    })
    ApiResponse<SettlementWebResponse> getSettlement(UUID settlementId);

    @Operation(
            summary = "정산 초안 수정",
            description = "현재 로그인한 생성자가 DRAFT 상태 정산의 제목 또는 총액을 수정합니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED, "
                    + "DRAFT가 아니면 SETTLEMENT_NOT_EDITABLE, 다른 요청이 먼저 수정했으면 CONCURRENT_UPDATE, "
                    + "입력이 유효하지 않으면 INVALID_ARGUMENT을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 요청값 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님; "
                            + "CONCURRENT_UPDATE - 다른 요청이 먼저 정산을 수정함"
            )
    })
    ApiResponse<SettlementWebResponse> updateSettlement(
            UUID settlementId,
            UpdateSettlementDraftWebRequest request
    );
}
