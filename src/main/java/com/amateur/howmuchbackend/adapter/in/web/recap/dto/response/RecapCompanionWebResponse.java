package com.amateur.howmuchbackend.adapter.in.web.recap.dto.response;

import com.amateur.howmuchbackend.application.port.in.recap.result.RecapCompanionResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record RecapCompanionWebResponse(
        @Schema(allowableValues = {"MEMBER", "GUEST", "WITHDRAWN_MEMBER"}, requiredMode = Schema.RequiredMode.REQUIRED) String kind,
        @Schema(description = "개인: 고정 이름, 공유: 공개 ID 또는 가린 이름", requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(description = "본인 인증이 필요한 고정 사진 주소. 사진 없음은 null", nullable = true) String avatarUrl,
        @Schema(description = "함께한 완료 정산 수", requiredMode = Schema.RequiredMode.REQUIRED) int completedSettlementCount,
        @Schema(description = "함께한 정산들의 본인 부담액 합계, 원", requiredMode = Schema.RequiredMode.REQUIRED) long totalOwnAmount) {
    public static RecapCompanionWebResponse from(RecapCompanionResult c, String month) {
        return c == null ? null : new RecapCompanionWebResponse(c.kind(), c.displayName(),
                c.hasAvatar() ? "/api/v2/recaps/" + month + "/companion/avatar" : null, c.completedSettlementCount(), c.totalOwnAmount());
    }
}
