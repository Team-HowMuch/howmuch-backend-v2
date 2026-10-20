package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public record WriteRecurringSettlementCommand(String title, long amount, String categoryCode,
        RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,
        LocalDate firstRunDate, List<Long> participantUserIds, Long receivingAccountId) {
}
