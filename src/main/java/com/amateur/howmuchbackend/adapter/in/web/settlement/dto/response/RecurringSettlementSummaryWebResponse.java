package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementDisplayState;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.UUID;

public record RecurringSettlementSummaryWebResponse(
        UUID id,
        Long groupId,
        String title,
        Category category,
        Cadence cadence,
        long totalAmount,
        Long myAmount,
        boolean isParticipant,
        RecurringSettlement.Status status,
        RecurringSettlementDisplayState displayState,
        LocalDateTime nextRunAt,
        Long daysUntilNextRun,
        java.util.List<RecurringSettlementParticipantWebResponse> participants,
        RecurringSettlementAccountWebResponse receivingAccount
) {
    public record Category(String code, String name, String icon) {
    }

    public record Cadence(RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth) {
    }

    public static RecurringSettlementSummaryWebResponse from(RecurringSettlementSummaryResult value) {
        return new RecurringSettlementSummaryWebResponse(value.id(), value.groupId(), value.title(),
                new Category(value.categoryCode(), value.categoryName(), value.categoryIcon()),
                new Cadence(value.frequency(), value.dayOfWeek(), value.dayOfMonth()),
                value.totalAmount(), value.myAmount(), value.isParticipant(), value.status(),
                value.displayState(), value.nextRunAt(), value.daysUntilNextRun(),
                value.presentation() == null ? java.util.List.of() : value.presentation().participants().stream()
                        .map(person -> RecurringSettlementParticipantWebResponse.from(value.groupId(), person)).toList(),
                RecurringSettlementAccountWebResponse.from(value.presentation()));
    }
}
