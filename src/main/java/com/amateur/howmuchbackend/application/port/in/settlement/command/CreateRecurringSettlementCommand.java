package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 반복 정산 등록 입력. {@code firstRunDate}는 생략할 수 있고, 생략 시 Application의 공용 resolver가
 * 주입된 Clock으로 가장 가까운 미래 유효 일정을 계산한다. {@code categoryCode}는 설정의 기본 카테고리다.
 */
public record CreateRecurringSettlementCommand(
        UUID sourceSettlementId,
        RecurrenceSchedule.Frequency frequency,
        DayOfWeek dayOfWeek,
        Integer dayOfMonth,
        LocalDate firstRunDate,
        String categoryCode
) {
}
