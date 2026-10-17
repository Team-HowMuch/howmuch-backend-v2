package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 등록과 미리보기가 공유하는 일정 결정기. 주입된 {@link Clock}으로 가장 가까운 <b>미래</b> 회차를 계산한다.
 *
 * <p>{@code firstRunDate}를 명시하면 주기와 정렬·미래 여부를 검증한 뒤 그대로 쓰고, 생략하면 오늘 KST
 * 날짜의 유효 회차 시각이 이미 지났는지에 따라 오늘/다음 주/다음 달을 고른다. 날짜 계산은 도메인
 * {@link RecurrenceSchedule}에만 두고 여기서 중복 구현하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RecurrenceScheduleResolver {
    private final Clock clock;

    public RecurrenceSchedule resolve(RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,
            LocalDate firstRunDate) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (firstRunDate != null) {
            var explicit = new RecurrenceSchedule(frequency, dayOfWeek, dayOfMonth, firstRunDate);
            explicit.requireFutureFirstRun(now);
            return explicit;
        }
        LocalDate kstToday = now.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(RecurrenceSchedule.BUSINESS_ZONE).toLocalDate();
        LocalDate candidate = RecurrenceSchedule.scheduledDateOnOrAfter(frequency, dayOfWeek, dayOfMonth, kstToday);
        if (!RecurrenceSchedule.runAt(candidate).isAfter(now)) {
            candidate = RecurrenceSchedule.scheduledDateOnOrAfter(frequency, dayOfWeek, dayOfMonth,
                    candidate.plusDays(1));
        }
        return new RecurrenceSchedule(frequency, dayOfWeek, dayOfMonth, candidate);
    }
}
