package com.amateur.howmuchbackend.adapter.in.web.treasurer.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.ReportTreasurerAnswerWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerAnswerReportWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerAnswerWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerChatWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerRecommendationsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

import java.util.UUID;

@Tag(name = "AI 비서 API", description = "개인 정산 채팅 비서. 조회만 수행하며 정산 데이터를 변경하지 않습니다.")
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
            description = "자유 대화와 바로가기·메뉴 칩을 같은 계약으로 처리합니다. view가 LIST이면 날짜·헤드라인 카드, SUMMARY이면 낼/받을/완료 요약 카드, TEXT이면 안내 문장입니다. 바로가기 질문 예: 보낼 정산, 받을 정산, 정산 상태. items의 allocationType과 status는 카드를 눌렀을 때 이동할 화면을 앱이 정하는 값입니다. allocationType은 배분 방식을 정하기 전이면 null이고, status는 정산 목록 응답과 같은 값입니다. "
                    + "\"뭐부터 처리해?\"처럼 먼저 처리할 정산을 물으면 intent PRIORITY_SETTLEMENTS로 추천 API와 같은 순서의 카드를 반환합니다. 이때 기간을 말하지 않으면 끝나지 않은 정산 전체를 보고 period는 null입니다. "
                    + "보낼·받을 정산 목록 카드도 같은 순서입니다. 맨 위가 24시간 이상 지난 보낼 정산이면 답변에 경과 시간 안내가 붙습니다.",
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
            description = "미완료이면서 이미 요청된 정산을 priority 순서(SEND_OVERDUE → SEND_OPEN → RECEIVE_OVERDUE → RECEIVE_OPEN)로 안내합니다. "
                    + "OVERDUE는 요청됨·입금 대기 정산이 요청 후 24시간 이상 지난 경우이고, 중단된 정산은 OPEN입니다. "
                    + "같은 단계 안에서는 요청이 오래된 정산, 금액이 큰 정산이 먼저입니다. "
                    + "작성 중인 DRAFT는 제외합니다. 화면 이동 액션만 제공하며, 송금·입금 확인은 AWAITING_DEPOSIT에서만 노출합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")
    })
    ApiResponse<TreasurerRecommendationsWebResponse> getRecommendations();

    @Operation(
            summary = "AI 비서 답변 신고",
            description = "질의 응답으로 받은 answerId의 답변을 신고합니다. 본인이 받은 답변만 신고할 수 있고, "
                    + "한 답변에는 한 번만 신고할 수 있습니다. 남의 답변, 없는 답변, 보관 기간이 지나 정리된 답변은 "
                    + "모두 404로 같게 응답합니다. answerId가 null인 답변은 저장에 실패한 것이라 신고할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "신고 접수", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "BAD_REQUEST"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "TREASURER_ANSWER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "TREASURER_ANSWER_ALREADY_REPORTED")
    })
    ApiResponse<TreasurerAnswerReportWebResponse> report(
            @Parameter(description = "신고할 답변 식별자", required = true) UUID answerId,
            @Valid ReportTreasurerAnswerWebRequest request
    );
}
