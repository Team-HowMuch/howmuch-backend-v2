package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SelectSettlementReceivingAccountWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReceivingAccountWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

@Tag(name = "정산 API", description = "정산 초안과 참여자 관리 API")
public interface SettlementReceivingAccountDocs {
    @Operation(
            summary = "정산 수령 계좌 후보 조회",
            description = "정산 생성자의 활성 원화 입출금 계좌를 기본 계좌 우선 순서로 조회합니다. "
                    + "초안 정산의 생성자만 조회할 수 있습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수령 계좌 후보 조회 성공",
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
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 정산이 아님"
            )
    })
    ApiResponse<List<SettlementReceivingAccountWebResponse>> getReceivingAccounts(
            UUID settlementId
    );

    @Operation(
            summary = "정산 수령 계좌 선택",
            description = "정산 생성자의 활성 원화 입출금 계좌 하나를 초안의 수령 계좌로 선택합니다. "
                    + "이미 선택된 계좌는 다른 후보로 교체할 수 있습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수령 계좌 선택 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 계좌 ID 검증 실패"
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
                    description = "SETTLEMENT_NOT_FOUND 또는 ACCOUNT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 정산이 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED - 수령 불가 계좌"
            )
    })
    ApiResponse<SettlementWebResponse> selectReceivingAccount(
            UUID settlementId,
            SelectSettlementReceivingAccountWebRequest request
    );
}
