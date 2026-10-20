package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementRequestWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementShareLinkWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 요청 API", description = "정산 확정과 공통 링크 발급 API")
public interface SettlementRequestDocs {
    @Operation(
            summary = "정산 요청 확정과 공통 링크 발급",
            description = "초안의 참여자·지출·배분·수령 계좌를 검증하고 하나의 공통 링크를 발급합니다. "
                    + "더치페이는 전원 배분 합계가 총액과 일치하면 양수 금액만 확정해 입금 대기 상태로 전환합니다. "
                    + "생성자를 포함해 0원 배분을 허용하며, 0원 참여자는 참여 기록만 유지하고 정산 요청 알림·납부 대상에서 제외합니다. "
                    + "품목별 배분은 참여자의 품목 선택을 기다리는 요청 상태로 전환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 요청 확정과 공통 링크 발급 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Share 금액 또는 정산 데이터 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND, ACCOUNT_NOT_FOUND 또는 USER_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "초안 상태, 참여자, 지출, 배분, 수령 계좌 또는 Share 합계 조건 불충족"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED - 수령 불가 계좌"
            )
    })
    ApiResponse<SettlementRequestWebResponse> requestSettlement(UUID settlementId);

    @Operation(
            summary = "진행 중 정산 공유 링크 조회",
            description = "정산 생성자가 요청 또는 입금 대기 상태의 활성 공유 URL을 다시 조회합니다. "
                    + "기존 hash-only 링크 또는 만료 링크는 최초 조회 시 새 링크로 교체합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공유 링크 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_LINK_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_SHARE_LINK_NOT_AVAILABLE - 진행 중 활성 링크가 아님"
            )
    })
    ApiResponse<SettlementShareLinkWebResponse> getShareLink(UUID settlementId);
}
