package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 독촉 결과")
public record SettlementReminderWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime remindedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime nextAvailableAt
) {
    public static SettlementReminderWebResponse from(SettlementReminderResult result) {
        return new SettlementReminderWebResponse(
                result.remindedAt(),
                result.nextAvailableAt()
        );
    }
}
