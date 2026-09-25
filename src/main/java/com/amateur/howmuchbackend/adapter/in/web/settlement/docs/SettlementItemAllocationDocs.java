package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "공개 정산 품목 배분 API", description = "공통 링크 참여자의 품목 선택·제출 API")
public interface SettlementItemAllocationDocs {
    @Operation(
            summary = "내 품목 배분 상태 조회",
            description = "링크와 참여 문맥이 가리키는 참여자의 품목 선택, "
                    + "옵션·할인, 예정 금액, 전체 제출 현황을 조회합니다. "
                    + "discountShare는 현재 선택 기준의 할인 몫이며 전체 제출 후 확정됩니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "품목 배분 상태 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_ALLOCATION_REQUIRED 또는 SETTLEMENT_ITEM_REQUIRED"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> get(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "정산 참여자 ID") UUID participantId,
            @Parameter(
                    name = "Link-Participant-Context",
                    in = ParameterIn.HEADER,
                    description = "링크 진입 후 발급된 단기 참여 문맥",
                    required = true
            ) UUID participantContext
    );

    @Operation(
            summary = "품목 부담 비율 선택",
            description = "ONE·HALF·THIRD·QUARTER 중 하나와 수량(units, 생략 시 1)을 저장합니다. "
                    + "의미는 units × fraction 이라 3인분 중 2인분은 units=2, fraction=THIRD 입니다. "
                    + "품목의 선택 합은 품목 수량을 초과할 수 없습니다. "
                    + "요청 자체가 품목 한 개 분량을 넘으면 INVALID_SETTLEMENT_ITEM_SELECTION_UNITS, "
                    + "다른 참여자가 먼저 가져가 남은 몫이 모자라면 SETTLEMENT_ITEM_OVER_ALLOCATED 입니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "품목 선택 저장 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT 또는 INVALID_SETTLEMENT_ITEM_ALLOCATION_FRACTION"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND, SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND "
                            + "또는 SETTLEMENT_ITEM_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE, "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS "
                            + "또는 SETTLEMENT_ITEM_OVER_ALLOCATED"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> save(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "정산 참여자 ID") UUID participantId,
            @Parameter(description = "정산 품목 ID") UUID itemId,
            @Parameter(
                    name = "Link-Participant-Context",
                    in = ParameterIn.HEADER,
                    description = "링크 진입 후 발급된 단기 참여 문맥",
                    required = true
            ) UUID participantContext,
            SaveSettlementItemSelectionWebRequest request
    );

    @Operation(
            summary = "품목 부담 선택 취소",
            description = "아직 제출하지 않은 참여자가 자신의 품목 선택을 취소합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "품목 선택 취소 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND, SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND, "
                            + "SETTLEMENT_ITEM_NOT_FOUND 또는 SETTLEMENT_ITEM_SELECTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE 또는 "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> remove(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "정산 참여자 ID") UUID participantId,
            @Parameter(description = "정산 품목 ID") UUID itemId,
            @Parameter(
                    name = "Link-Participant-Context",
                    in = ParameterIn.HEADER,
                    description = "링크 진입 후 발급된 단기 참여 문맥",
                    required = true
            ) UUID participantContext
    );

    @Operation(
            summary = "품목 선택 제출",
            description = "자신의 선택을 잠그고 제출합니다. 마지막 참여자가 제출하면 "
                    + "전체 배분을 검증해 Share와 AWAITING_DEPOSIT을 생성하거나, "
                    + "미배분 품목이 있으면 Share 없이 SUSPENDED로 전환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "제출 저장 또는 전체 배분 확정 완료",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_ITEM_SELECTION_NOT_EDITABLE 또는 "
                            + "SETTLEMENT_ITEM_SUBMISSION_ALREADY_EXISTS"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> submit(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "정산 참여자 ID") UUID participantId,
            @Parameter(
                    name = "Link-Participant-Context",
                    in = ParameterIn.HEADER,
                    description = "링크 진입 후 발급된 단기 참여 문맥",
                    required = true
            ) UUID participantContext
    );
}
