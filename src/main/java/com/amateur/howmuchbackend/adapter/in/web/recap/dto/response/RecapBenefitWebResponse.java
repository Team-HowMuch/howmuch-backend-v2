package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapBenefitResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "생성 시점에 고정한 제휴 혜택", requiredProperties = {"title", "reason", "expiresAt", "url", "logoUrl"})
public record RecapBenefitWebResponse(
        @Schema(description = "혜택 제목") String title,
        @Schema(description = "소비 패턴에 따른 추천 이유") String reason,
        @Schema(description = "UTC 혜택 만료 시각") LocalDateTime expiresAt,
        @Schema(description = "혜택 안내 URL") String url,
        @Schema(description = "제휴처 로고 URL, 없으면 null", nullable = true) String logoUrl) {
    public static RecapBenefitWebResponse from(RecapBenefitResult benefit) {
        return new RecapBenefitWebResponse(benefit.title(), benefit.reason(), benefit.expiresAt(), benefit.url(), benefit.logoUrl());
    }
}
