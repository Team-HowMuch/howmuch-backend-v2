package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 독촉 결과")
public record SettlementReminderWebResponse(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "독촉 요청이 성공한 UTC 시각. offset 없는 값을 UTC로 해석"
        )
        LocalDateTime remindedAt,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "같은 참여자를 다시 독촉할 수 있는 UTC 시각. "
                        + "offset 없는 값을 UTC로 해석"
        )
        LocalDateTime nextAvailableAt
) {
    public static SettlementReminderWebResponse from(SettlementReminderResult result) {
        return new SettlementReminderWebResponse(
                result.remindedAt(),
                result.nextAvailableAt()
        );
    }
}
