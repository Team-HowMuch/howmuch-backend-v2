package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ImportSettlementPaymentExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseMutationWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementPaymentExpenseCandidateWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Tag(name = "Settlement Payment Expense", description = "기존 결제내역 정산 지출 가져오기 API")
public interface SettlementPaymentExpenseDocs {
    @Operation(
            summary = "결제내역 정산 지출 후보 조회",
            description = "DRAFT 정산의 생성자가 본인 연결 계좌의 월별 출금 거래를 조회합니다. "
                    + "이미 같은 정산에 가져온 거래는 imported=true로 반환합니다. "
                    + "yearMonth를 생략하면 Asia/Seoul 기준 현재 월을 사용합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "결제내역 후보 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_ACCOUNT_QUERY 또는 "
                            + "INVALID_SETTLEMENT_PAYMENT_IMPORT_REQUEST"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 ACCOUNT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 "
                            + "SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "ACCOUNT_TRANSACTION_NOT_SUPPORTED"
            )
    })
    ApiResponse<List<SettlementPaymentExpenseCandidateWebResponse>> getPaymentExpenseCandidates(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "연결 계좌 ID") @Positive Long accountId,
            @Parameter(description = "조회 월(YYYY-MM), 생략 시 현재 월") YearMonth yearMonth
    );

    @Operation(
            summary = "결제내역 정산 지출 가져오기",
            description = "DRAFT 정산의 생성자가 본인 계좌의 출금 거래 한 건을 정산 지출로 가져옵니다. "
                    + "입금·소수 원 거래와 같은 정산의 중복 거래는 거부합니다. "
                    + "가져온 지출은 수정할 수 없지만 기존 지출 DELETE API로 제거할 수 있습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "결제내역 가져오기 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_SETTLEMENT_PAYMENT_IMPORT_REQUEST 또는 "
                            + "INVALID_SETTLEMENT_EXPENSE_TOTAL"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND, ACCOUNT_NOT_FOUND 또는 "
                            + "SETTLEMENT_PAYMENT_TRANSACTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE, "
                            + "SETTLEMENT_PAYMENT_TRANSACTION_NOT_IMPORTABLE 또는 "
                            + "SETTLEMENT_EXPENSE_ALREADY_IMPORTED"
            )
    })
    ApiResponse<SettlementExpenseMutationWebResponse> importPaymentExpense(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Valid ImportSettlementPaymentExpenseWebRequest request
    );
}
