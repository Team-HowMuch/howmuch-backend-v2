package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.UUID;

@Tag(
        name = "Settlement Member Item Allocation",
        description = "참여자 본인이 앱에서 품목 몫을 고르는 API. 링크 토큰이 필요 없다."
)
public interface SettlementMemberItemAllocationDocs {
    @Operation(
            summary = "내 품목 몫 조회",
            description = "로그인 회원이 이 정산의 참여자면 자기 몫을 조회합니다. "
                    + "deliveryFee는 전체 순 배달비, deliveryFeeShare는 품목 순액 비율로 계산한 내 배달비 몫입니다. "
                    + "전체 제출 전에는 잠정 금액이며 배분 확정 후 원 단위 잔액을 반영합니다. "
                    + "참여자가 아니면 SETTLEMENT_ACCESS_DENIED를 반환합니다. "
                    + "생성자 전용 my-items와 달리 DRAFT에서는 동작하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공", useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 이 정산의 참여자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_ITEM_REQUIRED"
            )
    })
    ApiResponse<SettlementItemAllocationWebResponse> getMyAllocation(
            @Parameter(description = "정산 ID") UUID settlementId
    );

    @Operation(
            summary = "내 품목 몫 저장",
            description = "몫은 품목 한 개에 대한 비율(ONE·HALF·THIRD·QUARTER) × units로 표현합니다. "
                    + "REQUESTED 상태에서만 고칠 수 있고 이미 제출했으면 거부합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementItemAllocationWebResponse> saveMySelection(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "품목 ID") UUID itemId,
            @Valid SaveSettlementItemSelectionWebRequest request
    );

    @Operation(summary = "내 품목 몫 취소", security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<SettlementItemAllocationWebResponse> removeMySelection(
            @Parameter(description = "정산 ID") UUID settlementId,
            @Parameter(description = "품목 ID") UUID itemId
    );

    @Operation(
            summary = "내 품목 몫 제출",
            description = "선택을 잠급니다. 전원이 제출하고 모든 품목이 채워지면 정산이 확정됩니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<SettlementItemAllocationWebResponse> submitMySelection(
            @Parameter(description = "정산 ID") UUID settlementId
    );
}
