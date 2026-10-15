package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ChangeMySettlementCategoryWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementMyCategoryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 카테고리", description = "정산 카테고리 목록과 내 카테고리 API")
public interface SettlementMyCategoryDocs {
    @Operation(
            summary = "내 카테고리 수정",
            description = "조회하는 회원 기준 카테고리(myCategory)를 바꿉니다. 다른 참여자의 카테고리는 바뀌지 않습니다. "
                    + "정산 생성자 또는 회원으로 연결된 참여자만 바꿀 수 있고, 정산 상태와 관계없이 바꿀 수 있습니다. "
                    + "categoryCode는 카테고리 목록 API의 code 중 하나이며 미분류(UNCATEGORIZED)도 고를 수 있습니다. "
                    + "같은 값을 다시 보내도 결과는 같고, 자동 분류가 나중에 끝나도 고른 값이 우선합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "저장된 내 카테고리",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 요청값 검증 실패(코드 누락·50자 초과, 형식이 틀린 settlementId·JSON 포함), "
                            + "INVALID_SETTLEMENT_CATEGORY - 카테고리 목록에 없는 코드"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자도, 연결된 참여자도 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 없거나 삭제된 정산"
            )
    })
    ApiResponse<SettlementMyCategoryWebResponse> changeMyCategory(
            UUID settlementId,
            ChangeMySettlementCategoryWebRequest request
    );
}
