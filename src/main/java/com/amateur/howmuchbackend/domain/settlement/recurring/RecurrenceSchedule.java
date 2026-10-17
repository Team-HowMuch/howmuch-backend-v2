package com.amateur.howmuchbackend.domain.settlement.recurring;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 그룹 반복 정산의 주기·회차 일정. 날짜는 Asia/Seoul 달력 기준이고 발행 시각은 09:00이다.
 *
 * <p>내부 저장 시각은 UTC {@link LocalDateTime} 규약을 따르므로, 09:00 KST는 같은 날짜 UTC 00:00이 된다.
 * 이 record는 값을 검증하고 회차 날짜를 계산할 뿐이며, 가장 가까운 미래 첫 회차를 고르는 정책은
 * Application의 {@code RecurrenceScheduleResolver}가 주입된 Clock으로 수행한다.
 */
public record RecurrenceSchedule(
        Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth, LocalDate firstRunDate
) {
    public enum Frequency { WEEKLY, MONTHLY }

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalTime REQUEST_TIME = LocalTime.of(9, 0);

    public RecurrenceSchedule {
        requireValidCadence(frequency, dayOfWeek, dayOfMonth);
        if (firstRunDate == null
                || (frequency == Frequency.WEEKLY && firstRunDate.getDayOfWeek() != dayOfWeek)
                || (frequency == Frequency.MONTHLY && firstRunDate.getDayOfMonth()
                        != Math.min(dayOfMonth, firstRunDate.lengthOfMonth()))) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRENCE_SCHEDULE);
        }
    }

    /**
     * 주기와 요일/날짜 조합만 검증한다. 첫 정산일 정렬·미래 여부는 별도로 본다. 등록 replay 비교처럼
     * 첫 정산일 없이 요청 형태만 확인할 때 쓴다.
     */
    public static void requireValidCadence(Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth) {
        boolean weekly = frequency == Frequency.WEEKLY && dayOfWeek != null && dayOfMonth == null;
        boolean monthly = frequency == Frequency.MONTHLY && dayOfWeek == null
                && dayOfMonth != null && dayOfMonth >= 1 && dayOfMonth <= 31;
        if (!weekly && !monthly) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRENCE_SCHEDULE);
        }
    }

    public LocalDateTime firstRunAt() {
        return runAt(firstRunDate);
    }

    public ZoneId zoneId() {
        return BUSINESS_ZONE;
    }

    public LocalTime requestTime() {
        return REQUEST_TIME;
    }

    public void requireFutureFirstRun(LocalDateTime now) {
        if (!firstRunAt().isAfter(now)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRENCE_SCHEDULE);
        }
    }

    public LocalDateTime nextAfter(LocalDateTime scheduledFor) {
        LocalDate date = scheduledFor.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        if (frequency == Frequency.WEEKLY) {
            return runAt(date.plusWeeks(1));
        }
        YearMonth next = YearMonth.from(date).plusMonths(1);
        return runAt(next.atDay(Math.min(dayOfMonth, next.lengthOfMonth())));
    }

    /** Asia/Seoul 기준 날짜의 09:00을 UTC {@link LocalDateTime}으로 변환한다. */
    public static LocalDateTime runAt(LocalDate kstDate) {
        return kstDate.atTime(REQUEST_TIME).atZone(BUSINESS_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * KST 날짜 {@code kstDate}가 속한 주/월에서 주기와 맞는 가장 빠른 회차 날짜를 돌려준다.
     * 주간이면 같은 주의 지정 요일(오늘이면 오늘), 월간이면 이번 달 날짜(말일 보정, 이미 지났으면 다음 달)다.
     */
    public static LocalDate scheduledDateOnOrAfter(Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,
            LocalDate kstDate) {
        if (frequency == Frequency.WEEKLY) {
            if (dayOfWeek == null) {
                throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRENCE_SCHEDULE);
            }
            int diff = (dayOfWeek.getValue() - kstDate.getDayOfWeek().getValue() + 7) % 7;
            return kstDate.plusDays(diff);
        }
        if (frequency != Frequency.MONTHLY || dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRENCE_SCHEDULE);
        }
        LocalDate candidate = kstDate.withDayOfMonth(Math.min(dayOfMonth, kstDate.lengthOfMonth()));
        if (!candidate.isBefore(kstDate)) {
            return candidate;
        }
        YearMonth next = YearMonth.from(kstDate).plusMonths(1);
        return next.atDay(Math.min(dayOfMonth, next.lengthOfMonth()));
    }
}
