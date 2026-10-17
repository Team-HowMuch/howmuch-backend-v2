package com.amateur.howmuchbackend.domain.settlement.recurring;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceScheduleTest {
    @Test
    void monthly_preservesNominalDayAcrossFebruary() {
        var schedule = monthly(31, LocalDate.of(2027, 1, 31));
        assertThat(schedule.firstRunAt()).isEqualTo(LocalDateTime.of(2027, 1, 31, 0, 0));
        var february = schedule.nextAfter(schedule.firstRunAt());
        assertThat(february).isEqualTo(LocalDateTime.of(2027, 2, 28, 0, 0));
        assertThat(schedule.nextAfter(february)).isEqualTo(LocalDateTime.of(2027, 3, 31, 0, 0));
    }

    @Test
    void monthly_supportsLeapYearAndYearBoundary() {
        var schedule = monthly(31, LocalDate.of(2027, 12, 31));
        var january = schedule.nextAfter(schedule.firstRunAt());
        assertThat(january).isEqualTo(LocalDateTime.of(2028, 1, 31, 0, 0));
        assertThat(schedule.nextAfter(january)).isEqualTo(LocalDateTime.of(2028, 2, 29, 0, 0));
    }

    @Test
    void weekly_usesKoreanNineAmAndKeepsWeekday() {
        var schedule = new RecurrenceSchedule(RecurrenceSchedule.Frequency.WEEKLY,
                DayOfWeek.MONDAY, null, LocalDate.of(2026, 10, 19));
        assertThat(schedule.firstRunAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0));
        assertThat(schedule.nextAfter(schedule.firstRunAt())).isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0));
    }

    @Test
    void firstRun_mustBeStrictlyFuture() {
        var schedule = monthly(31, LocalDate.of(2026, 10, 31));
        schedule.requireFutureFirstRun(schedule.firstRunAt().minusNanos(1));
        assertThatThrownBy(() -> schedule.requireFutureFirstRun(schedule.firstRunAt()))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> schedule.requireFutureFirstRun(schedule.firstRunAt().plusDays(1)))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void scheduledDateOnOrAfter_clampsAndRestoresMonthlyDay() {
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                LocalDate.of(2027, 2, 1))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                LocalDate.of(2027, 3, 1))).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.MONTHLY, null, 10,
                LocalDate.of(2027, 3, 10))).isEqualTo(LocalDate.of(2027, 3, 10));
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.MONTHLY, null, 10,
                LocalDate.of(2027, 3, 11))).isEqualTo(LocalDate.of(2027, 4, 10));
    }

    @Test
    void scheduledDateOnOrAfter_weeklyPicksNextMatchingWeekday() {
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.MONDAY,
                null, LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(RecurrenceSchedule.scheduledDateOnOrAfter(RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.MONDAY,
                null, LocalDate.of(2026, 10, 20))).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void invalidCombinationsAndMismatchedFirstDates_areRejected() {
        assertThatThrownBy(() -> monthly(31, LocalDate.of(2026, 10, 30))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> monthly(0, LocalDate.of(2026, 10, 31))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> new RecurrenceSchedule(RecurrenceSchedule.Frequency.WEEKLY,
                DayOfWeek.MONDAY, 1, LocalDate.of(2026, 10, 19))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> new RecurrenceSchedule(RecurrenceSchedule.Frequency.WEEKLY,
                DayOfWeek.TUESDAY, null, LocalDate.of(2026, 10, 19))).isInstanceOf(ApplicationException.class);
    }

    private RecurrenceSchedule monthly(int day, LocalDate first) {
        return new RecurrenceSchedule(RecurrenceSchedule.Frequency.MONTHLY, null, day, first);
    }
}
