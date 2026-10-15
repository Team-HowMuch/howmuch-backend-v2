package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementDepositSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 입금 확인 API", description = "CODEF 거래 기반 입금 대조와 진행 현황 API")
public interface SettlementDepositDocs {
    @Operation(
            summary = "정산 입금 대조 실행",
            description = "생성자가 수금 계좌의 최신 CODEF 수입 거래를 조회해 참여자 Share와 안전하게 대조합니다. "
                    + "입금자명은 유니코드·대소문자·공백 정규화 후 이름 전체가 거래 설명에 문자·숫자 경계로 포함되면 허용하며, "
                    + "이름 내부 공백과 공백으로 구분된 마지막 '입금'도 허용합니다. "
                    + "새 입금은 생성자에게 정산명·입금자·남은 금액을 알리고 전체 완료는 모두 받았어요 알림으로 구분합니다. "
                    + "정산 은행 조회는 서비스 공유·사용자 KST 일일 예산을 사용하며 총 100회, 자동 80회로 제한합니다. "
                    + "다른 이름의 부분 일치, 모호하거나 초과한 거래는 자동 반영하지 않으며 모든 납부 대상 Share가 확인되면 정산을 완료합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "입금 대조 성공",
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
                    description = "SETTLEMENT_NOT_FOUND, SETTLEMENT_DEPOSIT_DATA_NOT_FOUND 또는 EXTERNAL_CONNECTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE - 대조 불가 상태"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "TOO_MANY_REQUEST - 정산 은행 조회의 일일 예산 소진"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "CODEF_UNMAPPED_ERROR 또는 CODEF_RESPONSE_INVALID - CODEF 응답 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "CODEF_UPSTREAM_UNAVAILABLE - CODEF 조회 실패 또는 TEMPORARILY_UNAVAILABLE - 호출 예산 저장소 장애"
            )
    })
    ApiResponse<SettlementDepositSummaryWebResponse> reconcile(UUID settlementId);

    @Operation(
            summary = "정산 입금 현황 조회",
            description = "생성자가 저장된 입금 확인 이력 기준의 예상 금액, 확인 금액과 완료 참여자 수를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "입금 현황 조회 성공",
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
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_DEPOSIT_DATA_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE - 조회 불가 상태"
            )
    })
    ApiResponse<SettlementDepositSummaryWebResponse> get(UUID settlementId);
}
