package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "고정 지출 폼. 참여자는 생성자 포함 2명 이상. 균등 분배 후 나머지 원은 생성자가 부담한다.")
public record RecurringSettlementFormWebRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull @Positive Long amount,
        @NotBlank String categoryCode,
        @NotNull @Valid Cadence cadence,
        @Schema(description = "KST 첫 정산일. 생성 시 생략하면 가장 가까운 미래 일정. 수정 시 동일 주기이면 기존 다음 회차 유지.") LocalDate firstRunDate,
        @NotNull @Size(min = 2) List<@NotNull @Positive Long> participantUserIds,
        @NotNull @Positive Long receivingAccountId
) {
    public record Cadence(@NotNull RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek,
            @Min(1) @Max(31) Integer dayOfMonth) {}

    public WriteRecurringSettlementCommand toCommand() {
        return new WriteRecurringSettlementCommand(title.strip(), amount, categoryCode,
                cadence.frequency(), cadence.dayOfWeek(), cadence.dayOfMonth(), firstRunDate,
                participantUserIds, receivingAccountId);
    }
}
