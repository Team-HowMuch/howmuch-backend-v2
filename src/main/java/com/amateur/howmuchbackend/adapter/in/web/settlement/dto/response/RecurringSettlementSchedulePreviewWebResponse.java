package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSchedulePreviewResult;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record RecurringSettlementSchedulePreviewWebResponse(
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        LocalDate firstRunDate,
        LocalDateTime firstRunAt,
        String timezone,
        LocalTime requestTime
) {
    public static RecurringSettlementSchedulePreviewWebResponse from(RecurringSettlementSchedulePreviewResult value) {
        return new RecurringSettlementSchedulePreviewWebResponse(value.frequency(), value.dayOfWeek(),
                value.dayOfMonth(), value.firstRunDate(), value.firstRunAt(), value.timezone(), value.requestTime());
    }
}
