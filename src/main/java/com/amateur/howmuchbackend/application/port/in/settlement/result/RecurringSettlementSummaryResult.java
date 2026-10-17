package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementDisplayState;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 목록·홈 카드 요약. 회차당 금액(주간=주, 월간=월)이며 {@code myAmount}는 현재 사용자의 고정 부담액으로
 * 비참여자면 null이다. 다른 사용자의 계좌·비밀 정보는 포함하지 않는다.
 *
 * @param daysUntilNextRun KST 달력일 기준 D-day. 오늘이면 0, 지났으면 음수, 해지면 null
 */
public record RecurringSettlementSummaryResult(
        UUID id,
        Long groupId,
        String title,
        String categoryCode,
        String categoryName,
        String categoryIcon,
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        long totalAmount,
        Long myAmount,
        boolean isParticipant,
        RecurringSettlement.Status status,
        RecurringSettlementDisplayState displayState,
        LocalDateTime nextRunAt,
        Long daysUntilNextRun
) {
}
