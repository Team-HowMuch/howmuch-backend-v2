package com.amateur.howmuchbackend.adapter.in.web.treasurer.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerAnswerWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerChatWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerRecommendationsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI 총무 API", description = "개인 정산 채팅 비서. 조회만 수행하며 정산 데이터를 변경하지 않습니다.")
public interface TreasurerDocs {
    @Operation(
            summary = "AI 비서 채팅 시작",
            description = "인사 문구, 바로가기(보낼 정산/받을 정산/정산 상태), 메뉴 제안 칩을 반환합니다. 칩의 question을 질의 API에 그대로 보냅니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerChatWebResponse> getChat();

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
            description = "자유 대화와 바로가기·메뉴 칩을 같은 계약으로 처리합니다. view가 LIST이면 날짜·헤드라인 카드, SUMMARY이면 낼/받을/완료 요약 카드, TEXT이면 안내 문장입니다. 바로가기 질문 예: 보낼 정산, 받을 정산, 정산 상태.",
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
