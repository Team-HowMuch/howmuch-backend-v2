package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "초안 전환(sourceSettlementId + frequency) 또는 직접 등록(title/amount/cadence/participantUserIds/receivingAccountId) 중 하나만 입력한다.")
public record CreateRecurringSettlementWebRequest(
        UUID sourceSettlementId,
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        @Min(1) @Max(31) Integer dayOfMonth,
        LocalDate firstRunDate,
        @NotBlank String categoryCode,
        @Size(max = 100) String title,
        @Positive Long amount,
        @Valid RecurringSettlementFormWebRequest.Cadence cadence,
        @Size(min = 2) List<@NotNull @Positive Long> participantUserIds,
        @Positive Long receivingAccountId
) {
    @AssertTrue(message = "초안 전환 본문 또는 완전한 직접 등록 폼 중 하나만 입력해주세요.")
    @Schema(hidden = true)
    public boolean isValidRegistration() {
        if (sourceSettlementId != null) {
            return frequency != null && title == null && amount == null && cadence == null
                    && participantUserIds == null && receivingAccountId == null;
        }
        return frequency == null && dayOfWeek == null && dayOfMonth == null
                && title != null && !title.isBlank() && amount != null && cadence != null
                && participantUserIds != null && receivingAccountId != null;
    }

    public CreateRecurringSettlementCommand toCommand() {
        return new CreateRecurringSettlementCommand(sourceSettlementId, frequency, dayOfWeek, dayOfMonth,
                firstRunDate, categoryCode);
    }

    public WriteRecurringSettlementCommand toFormCommand() {
        return new RecurringSettlementFormWebRequest(title, amount, categoryCode, cadence, firstRunDate,
                participantUserIds, receivingAccountId).toCommand();
    }
}
