package com.amateur.howmuchbackend.adapter.in.web.treasurer.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerAnswerResponse;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerRecommendationsResponse;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI 총무 API", description = "개인 정산 현황 요약·질의·추천. 조회만 수행하며 정산 데이터를 변경하지 않습니다.")
public interface TreasurerDocs {
    @Operation(
            summary = "개인 정산 현황 요약",
            description = "로그인 사용자 기준 보낼/받을/진행 중/완료 건수와 금액을 집계하고 우선 안내 문구 1개를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerSummaryResponse> getSummary();

    @Operation(
            summary = "AI 정산·지출 질의",
            description = "보낼 정산, 받을 정산, 진행 상태, 지출 요약 의도를 키워드로 분류한 뒤 정산 목록을 조회합니다. LLM을 호출하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "질문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerAnswerResponse> ask(@Valid AskTreasurerWebRequest request);

    @Operation(
            summary = "확인이 필요한 정산 추천",
            description = "미완료 정산을 보낼/받을·장기 미처리 순으로 안내하고 화면 이동 액션만 제공합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerRecommendationsResponse> getRecommendations();
}
