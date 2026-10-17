package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 정규화된 반복 일정 미리보기. {@code firstRunAt}은 UTC ISO 시각이고, {@code timezone}/{@code requestTime}은
 * 클라이언트가 표시에 사용하는 발행 시간대·시각이다.
 */
public record RecurringSettlementSchedulePreviewResult(
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        LocalDate firstRunDate,
        LocalDateTime firstRunAt,
        String timezone,
        LocalTime requestTime
) {
    public static RecurringSettlementSchedulePreviewResult from(RecurrenceSchedule schedule) {
        return new RecurringSettlementSchedulePreviewResult(schedule.frequency(), schedule.dayOfWeek(),
                schedule.dayOfMonth(), schedule.firstRunDate(), schedule.firstRunAt(),
                schedule.zoneId().getId(), schedule.requestTime());
    }
}
