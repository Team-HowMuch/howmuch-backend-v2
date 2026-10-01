package com.amateur.howmuchbackend.adapter.in.web.analytics.docs;

import com.amateur.howmuchbackend.adapter.in.web.analytics.dto.response.AdminAnalyticsOverviewWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;

@Tag(name = "관리자 지표 API", description = "Amplitude에 수집된 이벤트 발생 건수 조회")
public interface AdminAnalyticsDocs {
    @Operation(summary = "관리자 지표 대시보드 조회",
            description = "활성 ADMIN 전용. KST 기준 양 끝 날짜를 포함하며 최대 30일입니다. 둘 다 생략하면 오늘 포함 최근 30일입니다. 이벤트 건수는 고유 사용자나 정산 수가 아닙니다. 평균 금액이 없는 날짜는 averageAmountTrend에서 생략됩니다. 독립 이벤트 건수 비교이며 사용자 전환 퍼널이 아닙니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_ARGUMENT - 날짜 누락·역전·30일 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN - 활성 ADMIN 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "TEMPORARILY_UNAVAILABLE - Amplitude 설정·조회·응답 오류")
    })
    ApiResponse<AdminAnalyticsOverviewWebResponse> overview(
            @Parameter(description = "KST 시작 날짜, YYYY-MM-DD") LocalDate from,
            @Parameter(description = "KST 종료 날짜, YYYY-MM-DD") LocalDate to
    );
}
