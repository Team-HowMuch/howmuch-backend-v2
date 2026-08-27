package com.amateur.howmuchbackend.adapter.in.web.treasurer.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerAnswerWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerRecommendationsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI 총무 API", description = "개인 정산 현황 요약·질의·추천. 조회만 수행하며 정산 데이터를 변경하지 않습니다.")
public interface TreasurerDocs {
    @Operation(
            summary = "개인 정산 현황 요약",
            description = "로그인 사용자 기준 보낼/받을/진행 중/완료 건수와 금액을 집계하고 우선 안내 문구 1개를 반환합니다. 보낼/받을 금액은 요청된 이후 미완료 정산만 합산하고, 작성 중인 DRAFT는 진행 중 건수에만 포함합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerSummaryWebResponse> getSummary();

    @Operation(
            summary = "AI 정산·지출 질의",
            description = "보낼 정산, 받을 정산, 진행 상태, 지출 요약 의도를 키워드로 분류한 뒤 정산 목록을 조회합니다. 기간과 제목이 함께 있으면 기간을 제외한 `○○모임`/`○○정산`만 제목 필터로 씁니다. 안내 문장은 설정된 LLM이 서버 팩트만으로 생성하고, 없거나 실패하거나 숫자를 지어내면 서버 템플릿을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "질문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerAnswerWebResponse> ask(@Valid AskTreasurerWebRequest request);

    @Operation(
            summary = "확인이 필요한 정산 추천",
            description = "미완료이면서 이미 요청된 정산을 보낼/받을·장기 미처리 순으로 안내합니다. 작성 중인 DRAFT는 제외합니다. 화면 이동 액션만 제공하며, 송금·입금 확인은 AWAITING_DEPOSIT에서만 노출합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerRecommendationsWebResponse> getRecommendations();
}
