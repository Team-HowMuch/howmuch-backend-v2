package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 목록·홈 요약을 위한 평범한 Application 값. 참여자·지출을 개별 조회하지 않고 focused 쿼리 하나로
 * 채운다. {@code viewerAmount}는 현재 사용자의 고정 부담액이며 비참여자면 null이다.
 */
public record RecurringSettlementSummaryReadModel(
        UUID id,
        Long groupId,
        String title,
        String categoryCode,
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        long totalAmount,
        Long viewerAmount,
        LocalDateTime nextRunAt,
        RecurringSettlement.Status status,
        int failureCount,
        LocalDateTime retryAt,
        String lastFailureCode,
        RecurringSettlement.CancellationReason cancellationReason
) {
}
