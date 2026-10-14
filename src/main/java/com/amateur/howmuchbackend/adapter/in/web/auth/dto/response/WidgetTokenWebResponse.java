package com.amateur.howmuchbackend.adapter.in.web.auth.dto.response;

import com.amateur.howmuchbackend.application.port.in.auth.result.WidgetTokenResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record WidgetTokenWebResponse(
        @Schema(description = "현재 로그인 세션에 묶인 위젯 전용 Bearer 토큰") String widgetToken,
        @Schema(description = "위젯 토큰 만료 시각 (UTC)") LocalDateTime expiresAt
) {
    public static WidgetTokenWebResponse from(WidgetTokenResult result) {
        return new WidgetTokenWebResponse(result.widgetToken(), result.expiresAt());
    }

    @Override
    public String toString() {
        return "WidgetTokenWebResponse[widgetToken=***, expiresAt=" + expiresAt + "]";
    }
}
