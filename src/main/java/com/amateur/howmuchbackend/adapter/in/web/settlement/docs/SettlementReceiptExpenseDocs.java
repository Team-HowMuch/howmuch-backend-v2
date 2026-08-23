package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ImportSettlementReceiptExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemListWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReceiptExpenseMutationWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.UUID;

@Tag(name = "Settlement Receipt Expense", description = "OCR 영수증 정산 지출·품목 API")
public interface SettlementReceiptExpenseDocs {
    @Operation(
            summary = "OCR 영수증 정산 지출 가져오기",
            description = "DRAFT 정산의 생성자가 외부 OCR 결과 참조로 영수증 지출과 품목을 "
                    + "한 번에 가져옵니다. OCR 호출은 DB 트랜잭션 밖에서 수행하고, 지출과 "
                    + "품목은 원자적으로 저장합니다. local·dev에서는 고정된 개발 Stub 결과를 "
                    + "반환하며 prod는 실제 OCR Adapter 연결 전까지 503을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "OCR 영수증 가져오기 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST "
                            + "또는 INVALID_SETTLEMENT_EXPENSE_TOTAL"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_RECEIPT_EXTRACTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE, SETTLEMENT_RECEIPT_ALREADY_IMPORTED "
                            + "또는 SETTLEMENT_RECEIPT_EXTRACTION_INVALID"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "SETTLEMENT_RECEIPT_EXTRACTION_UNAVAILABLE - 운영 OCR 미연결"
            )
    })
    ApiResponse<SettlementReceiptExpenseMutationWebResponse> importReceiptExpense(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Valid ImportSettlementReceiptExpenseWebRequest request
    );

    @Operation(
            summary = "정산 OCR 품목 목록 조회",
            description = "정산 생성자가 저장된 OCR 품목을 지출별 표시 순서대로 조회합니다. "
                    + "amount는 수량을 반영한 품목 행의 총 금액입니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "정산 품목 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "SETTLEMENT_NOT_FOUND"
            )
    })
    ApiResponse<SettlementItemListWebResponse> getSettlementItems(
            @Parameter(description = "정산 ID") UUID settlementId
    );
}
