package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SelectSettlementAllocationTypeWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDutchPayAllocationWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementAllocationTypeWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementDutchPayWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "Settlement Allocation", description = "정산 배분 유형과 더치페이 초안 API")
public interface SettlementAllocationDocs {
    @Operation(
            summary = "정산 배분 유형 선택",
            description = "DRAFT 정산의 생성자가 배분 유형을 선택합니다. 기준 총액과 지출 합계가 같아야 하며, "
                    + "DUTCH_PAY는 기본 N빵을 생성합니다. ITEM_ALLOCATION은 실제 OCR 품목을 검증할 수 있는 "
                    + "저장 기능이 구현되기 전까지 SETTLEMENT_ITEM_ALLOCATION_NOT_AVAILABLE을 반환합니다. "
                    + "같은 유형을 다시 선택하면 기존 더치페이 수정 금액을 유지합니다. "
                    + "지출 없음은 SETTLEMENT_EXPENSE_REQUIRED, 합계 불일치는 "
                    + "SETTLEMENT_EXPENSE_TOTAL_MISMATCH, 참여자 부족은 "
                    + "SETTLEMENT_PARTICIPANTS_INSUFFICIENT를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "배분 유형 선택 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT 또는 INVALID_SETTLEMENT_ALLOCATION_TYPE"
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
                    description = "SETTLEMENT_NOT_EDITABLE, SETTLEMENT_EXPENSE_REQUIRED, "
                            + "SETTLEMENT_EXPENSE_TOTAL_MISMATCH, "
                            + "SETTLEMENT_PARTICIPANTS_INSUFFICIENT 또는 "
                            + "SETTLEMENT_ITEM_ALLOCATION_NOT_AVAILABLE"
            )
    })
    ApiResponse<SettlementAllocationTypeWebResponse> selectAllocationType(
            @Parameter(description = "정산 ID") UUID settlementId,
            SelectSettlementAllocationTypeWebRequest request
    );

    @Operation(
            summary = "더치페이 배분 조회",
            description = "DRAFT 더치페이 정산의 참여자별 부담 금액과 현재 합계·차액을 조회합니다. "
                    + "배분 유형 미선택은 SETTLEMENT_ALLOCATION_NOT_SELECTED, 품목별 정산은 "
                    + "SETTLEMENT_DUTCH_PAY_REQUIRED를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "더치페이 배분 조회 성공",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE, SETTLEMENT_ALLOCATION_NOT_SELECTED, "
                            + "SETTLEMENT_DUTCH_PAY_REQUIRED 또는 "
                            + "SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE"
            )
    })
    ApiResponse<SettlementDutchPayWebResponse> getDutchPayAllocation(
            @Parameter(description = "정산 ID") UUID settlementId
    );

    @Operation(
            summary = "더치페이 참여자 금액 수정",
            description = "DRAFT 더치페이 정산에서 한 참여자의 금액을 0원 이상으로 수정합니다. "
                    + "수정 중 합계 불일치는 허용하고 differenceAmount와 balanced로 현재 상태를 반환합니다. "
                    + "참여자 또는 배분 행이 없으면 각각 SETTLEMENT_PARTICIPANT_NOT_FOUND, "
                    + "SETTLEMENT_DUTCH_PAY_ALLOCATION_NOT_FOUND를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "더치페이 참여자 금액 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_DUTCH_PAY_AMOUNT 또는 INVALID_DUTCH_PAY_TOTAL"
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
                    description = "SETTLEMENT_NOT_FOUND, SETTLEMENT_PARTICIPANT_NOT_FOUND 또는 "
                            + "SETTLEMENT_DUTCH_PAY_ALLOCATION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE, SETTLEMENT_ALLOCATION_NOT_SELECTED, "
                            + "SETTLEMENT_DUTCH_PAY_REQUIRED 또는 "
                            + "SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE"
            )
    })
    ApiResponse<SettlementDutchPayWebResponse> updateDutchPayAllocation(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "정산 참여자 ID") UUID participantId,
            UpdateSettlementDutchPayAllocationWebRequest request
    );
}
