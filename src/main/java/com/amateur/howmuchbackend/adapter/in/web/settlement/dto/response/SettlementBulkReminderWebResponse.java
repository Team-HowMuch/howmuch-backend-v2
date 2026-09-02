package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementBulkReminderResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 일괄 독촉 결과")
public record SettlementBulkReminderWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int sentCount,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true,
                description = "관련 참여자의 독촉 쿨다운이 모두 끝나는 UTC 시각. "
                        + "offset 없는 값을 UTC로 해석하며 활성 쿨다운이 없으면 null"
        )
        LocalDateTime nextAvailableAt
) {
    public static SettlementBulkReminderWebResponse from(
            SettlementBulkReminderResult result
    ) {
        return new SettlementBulkReminderWebResponse(
                result.sentCount(),
                result.nextAvailableAt()
        );
    }
}
