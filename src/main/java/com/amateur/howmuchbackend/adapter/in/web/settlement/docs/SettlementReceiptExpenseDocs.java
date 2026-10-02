package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementReceiptExpenseWebRequest;
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
            summary = "앱 OCR 영수증 정산 지출 등록",
            description = "DRAFT 정산의 생성자가 앱에서 인식한 상호명, 결제 시각, 품목을 "
                    + "등록합니다. items[].amount는 옵션을 포함한 할인 전 행 금액입니다. "
                    + "지출 amount는 Σ(items[].amount - items[].discount) - discount + deliveryFee입니다. "
                    + "deliveryFee는 배달팁 할인을 반영한 순액이며 생략/null은 0원입니다. "
                    + "전체 discount는 품목 순액 합보다 작아야 하며 배달비는 할인 상한에 포함하지 않습니다. "
                    + "옵션 수량은 상위 품목 수량을 따릅니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "영수증 지출 등록 성공",
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
                    responseCode = "404", description = "SETTLEMENT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 SETTLEMENT_RECEIPT_ALREADY_IMPORTED"
            )
    })
    ApiResponse<SettlementReceiptExpenseMutationWebResponse> createReceiptExpense(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Valid CreateSettlementReceiptExpenseWebRequest request
    );

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
                    + "amount와 totalItemAmount는 할인 전 품목 금액이며 배달비를 포함하지 않습니다. "
                    + "discount는 영수증 전체 할인, deliveryFee는 전체 순 배달비 합계입니다.",
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
