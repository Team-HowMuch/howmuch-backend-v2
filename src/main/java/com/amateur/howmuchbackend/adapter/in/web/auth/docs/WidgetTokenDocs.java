package com.amateur.howmuchbackend.adapter.in.web.auth.docs;

import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.WidgetTokenWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인증 API")
public interface WidgetTokenDocs {
    @Operation(summary = "위젯 전용 토큰 발급", security = @SecurityRequirement(name = "bearerAuth"),
            description = "앱 ACCESS 토큰으로 현재 세션 만료 시각까지 유효한 WIDGET 토큰을 발급합니다. "
                    + "위젯은 GET /api/v2/settlements/widget-summary와 "
                    + "POST /api/v2/settlements/widget-summary/refresh에만 이 토큰을 사용할 수 있습니다. "
                    + "로그아웃·세션 만료·새 로그인 시 401을 반환하므로 앱에서 다시 발급받으세요. "
                    + "위젯 토큰 자체는 갱신하거나 다른 API를 호출할 수 없습니다. expiresAt은 UTC입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "토큰 발급 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "UNAUTHORIZED - 유효한 앱 ACCESS 토큰과 로그인 세션 필요")
    })
    ApiResponse<WidgetTokenWebResponse> issue();
}
