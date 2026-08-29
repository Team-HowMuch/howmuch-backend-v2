package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementBulkReminderResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "정산 일괄 독촉 결과")
public record SettlementBulkReminderWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int sentCount
) {
    public static SettlementBulkReminderWebResponse from(
            SettlementBulkReminderResult result
    ) {
        return new SettlementBulkReminderWebResponse(result.sentCount());
    }
}
