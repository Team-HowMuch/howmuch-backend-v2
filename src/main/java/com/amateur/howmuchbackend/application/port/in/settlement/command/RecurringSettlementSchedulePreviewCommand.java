package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** 등록 전 일정 미리보기 입력. {@code firstRunDate}는 생략할 수 있다. */
public record RecurringSettlementSchedulePreviewCommand(
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        LocalDate firstRunDate
) {
}
