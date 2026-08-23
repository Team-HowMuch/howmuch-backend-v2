package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseListWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseMutationWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseTotalWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 지출 API", description = "정산 초안의 수동 지출 관리 API")
public interface SettlementExpenseDocs {
    @Operation(
            summary = "수동 정산 지출 등록",
            description = "현재 로그인한 생성자가 DRAFT 정산에 수동 지출을 등록합니다. "
                    + "등록된 지출과 전체 지출 누적액을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "수동 정산 지출 등록 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "INVALID_ARGUMENT - 설명 또는 금액 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님"
            )
    })
    ApiResponse<SettlementExpenseMutationWebResponse> createSettlementExpense(
            UUID settlementId,
            CreateSettlementExpenseWebRequest request
    );

    @Operation(
            summary = "정산 지출 목록 조회",
            description = "현재 로그인한 생성자가 정산 지출을 표시 순서대로 조회합니다. "
                    + "현재 저장된 전체 지출 누적액을 함께 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "정산 지출 목록 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            )
    })
    ApiResponse<SettlementExpenseListWebResponse> getSettlementExpenses(UUID settlementId);

    @Operation(
            summary = "수동 정산 지출 수정",
            description = "현재 로그인한 생성자가 DRAFT 정산의 수동 지출 설명 또는 금액을 수정합니다. "
                    + "수정된 지출과 최신 전체 지출 누적액을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "수동 정산 지출 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "INVALID_ARGUMENT - 수정 필드 또는 값 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_EXPENSE_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 SETTLEMENT_EXPENSE_NOT_EDITABLE"
            )
    })
    ApiResponse<SettlementExpenseMutationWebResponse> updateSettlementExpense(
            UUID settlementId,
            UUID expenseId,
            UpdateSettlementExpenseWebRequest request
    );

    @Operation(
            summary = "수동 정산 지출 삭제",
            description = "현재 로그인한 생성자가 DRAFT 정산의 수동 지출을 삭제합니다. "
                    + "삭제 뒤 최신 전체 지출 누적액을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "수동 정산 지출 삭제 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_EXPENSE_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 SETTLEMENT_EXPENSE_NOT_EDITABLE"
            )
    })
    ApiResponse<SettlementExpenseTotalWebResponse> removeSettlementExpense(
            UUID settlementId,
            UUID expenseId
    );
}
