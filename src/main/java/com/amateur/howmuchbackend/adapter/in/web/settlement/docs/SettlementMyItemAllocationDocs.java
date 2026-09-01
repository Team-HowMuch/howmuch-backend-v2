package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(
        name = "Settlement My Item Allocation",
        description = "정산 생성자가 알림 발송 전에 자기 품목 몫을 고르는 API. "
                + "요청·응답 형태는 링크 경로(Settlement Item Allocation)와 동일하다."
)
public interface SettlementMyItemAllocationDocs {
    @Operation(
            summary = "내 품목 몫 조회",
            description = "품목별 할당 정산에서 생성자 본인의 품목 목록과 현재 선택을 조회합니다. "
                    + "알림 발송 전 DRAFT 상태에서도 호출할 수 있는 점만 링크 경로와 다릅니다. "
                    + "배분 유형이 품목별이 아니면 SETTLEMENT_ITEM_ALLOCATION_REQUIRED, "
                    + "품목이 없으면 SETTLEMENT_ITEM_REQUIRED를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음 또는 품목 선택이 불가능한 상태"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_ALLOCATION_REQUIRED, SETTLEMENT_ITEM_REQUIRED "
                            + "또는 SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> getMyItems(
            @Parameter(description = "정산 ID") UUID settlementId
    );

    @Operation(
            summary = "내 품목 몫 저장",
            description = "생성자 본인이 부담할 품목 몫을 저장합니다. "
                    + "몫은 품목 한 개에 대한 비율(ONE·HALF·THIRD·QUARTER) × units로 표현합니다. "
                    + "예: 2개는 fraction=ONE·units=2, 1/3개는 fraction=THIRD·units=1. "
                    + "DRAFT와 REQUESTED에서만 수정할 수 있고 이미 제출했으면 거부합니다. "
                    + "품목의 선택 합이 수량을 넘으면 SETTLEMENT_ITEM_OVER_ALLOCATED를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "저장 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT 또는 INVALID_SETTLEMENT_ITEM_ALLOCATION_FRACTION"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_ITEM_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE, "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS "
                            + "또는 SETTLEMENT_ITEM_OVER_ALLOCATED"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> saveMyItem(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "정산 품목 ID") UUID itemId,
            SaveSettlementItemSelectionWebRequest request
    );

    @Operation(
            summary = "내 품목 몫 취소",
            description = "생성자 본인의 품목 선택을 취소합니다. "
                    + "선택이 없으면 SETTLEMENT_ITEM_SELECTION_NOT_FOUND를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "취소 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND, SETTLEMENT_ITEM_NOT_FOUND 또는 "
                            + "SETTLEMENT_ITEM_SELECTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE 또는 "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> removeMyItem(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "정산 품목 ID") UUID itemId
    );

    @Operation(
            summary = "내 품목 몫 제출",
            description = "생성자 본인의 품목 선택을 확정합니다. 제출 후에는 수정할 수 없습니다. "
                    + "**DRAFT에서 제출해도 정산 상태는 그대로입니다.** "
                    + "확정(AWAITING_DEPOSIT 또는 SUSPENDED)은 정산 요청 이후 참여자 전원이 "
                    + "제출했을 때만 일어납니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "제출 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE 또는 "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> submitMyItems(
            @Parameter(description = "정산 ID") UUID settlementId
    );
}
