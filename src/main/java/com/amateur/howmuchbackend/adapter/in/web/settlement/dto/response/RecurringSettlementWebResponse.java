package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record RecurringSettlementWebResponse(UUID id, Long groupId, Long creatorId, String title,
        String categoryCode, long totalAmount, Long receivingAccountId, Schedule schedule,
        RecurringSettlement.Status status, List<Participant> participants, List<Expense> expenses,
        LocalDateTime nextRunAt, int failureCount, LocalDateTime retryAt, String lastFailureCode,
        LocalDateTime lastFailureAt, boolean requiresManualReview,
        RecurringSettlement.CancellationReason cancellationReason, LocalDateTime cancelledAt,
        LocalDateTime createdAt) {
    public record Schedule(RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek,
            Integer dayOfMonth, LocalDate firstRunDate, String timezone, LocalTime requestTime) {
    }

    public record Participant(Long userId, long amount, int displayOrder) {
    }

    public record Expense(String description, long amount, int displayOrder) {
    }

    public static RecurringSettlementWebResponse from(RecurringSettlementResult value) {
        var schedule = value.schedule();
        return new RecurringSettlementWebResponse(value.id(), value.groupId(), value.creatorId(), value.title(),
                value.categoryCode(), value.totalAmount(), value.receivingAccountId(),
                new Schedule(schedule.frequency(), schedule.dayOfWeek(), schedule.dayOfMonth(),
                        schedule.firstRunDate(), schedule.zoneId().getId(), schedule.requestTime()),
                value.status(),
                value.participants().stream().map(person -> new Participant(person.userId(), person.amount(),
                        person.displayOrder())).toList(),
                value.expenses().stream().map(cost -> new Expense(cost.description(), cost.amount(),
                        cost.displayOrder())).toList(),
                value.nextRunAt(), value.failureCount(), value.retryAt(), value.lastFailureCode(),
                value.lastFailureAt(), value.requiresManualReview(), value.cancellationReason(),
                value.cancelledAt(), value.createdAt());
    }
}
