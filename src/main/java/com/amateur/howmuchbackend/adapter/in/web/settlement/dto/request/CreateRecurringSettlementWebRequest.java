package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

public record CreateRecurringSettlementWebRequest(
        @NotNull @Schema(description = "반복 설정으로 전환할 정산 초안 ID") UUID sourceSettlementId,
        @NotNull RecurrenceSchedule.Frequency frequency,
        @Schema(description = "매주 선택 시 요일, 매월이면 null") DayOfWeek dayOfWeek,
        @Min(1) @Max(31) @Schema(description = "매월 선택 시 날짜, 매주이면 null") Integer dayOfMonth,
        @Schema(description = "한국 날짜 기준 첫 정산일. 생략하면 가장 가까운 미래 유효 일정을 서버가 계산한다.",
                example = "2026-10-31")
        LocalDate firstRunDate,
        @NotBlank @Schema(description = "설정의 기본 카테고리 코드. GET /api/v2/settlement-categories의 선택 가능 코드",
                example = "HOUSING_TELECOM")
        String categoryCode
) {
    public CreateRecurringSettlementCommand toCommand() {
        return new CreateRecurringSettlementCommand(sourceSettlementId, frequency, dayOfWeek, dayOfMonth,
                firstRunDate, categoryCode);
    }
}
