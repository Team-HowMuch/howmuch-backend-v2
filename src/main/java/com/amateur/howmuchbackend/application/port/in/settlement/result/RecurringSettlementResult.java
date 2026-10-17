package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RecurringSettlementResult(UUID id, Long groupId, Long creatorId, String title,
        String categoryCode, long totalAmount, Long receivingAccountId, RecurrenceSchedule schedule,
        RecurringSettlement.Status status, List<RecurringSettlement.Participant> participants,
        List<RecurringSettlement.Expense> expenses, LocalDateTime nextRunAt, int failureCount,
        LocalDateTime retryAt, String lastFailureCode, LocalDateTime lastFailureAt,
        boolean requiresManualReview, RecurringSettlement.CancellationReason cancellationReason,
        LocalDateTime cancelledAt, LocalDateTime createdAt) {
    public static RecurringSettlementResult from(RecurringSettlement value) {
        return new RecurringSettlementResult(value.id(), value.groupId(), value.creatorId(), value.title(),
                value.categoryCode(), value.totalAmount(), value.receivingAccountId(), value.schedule(),
                value.status(), value.participants(), value.expenses(), value.nextRunAt(), value.failureCount(),
                value.retryAt(), value.lastFailureCode(), value.lastFailureAt(), value.requiresManualReview(),
                value.cancellationReason(), value.cancelledAt(), value.createdAt());
    }
}
