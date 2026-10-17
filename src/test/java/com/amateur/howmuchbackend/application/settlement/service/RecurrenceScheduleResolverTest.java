package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceScheduleResolverTest {
    private static RecurrenceScheduleResolver resolverAt(String utcInstant) {
        return new RecurrenceScheduleResolver(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
    }

    @Test
    void monthlyBeforeNineKeepsToday_andAtOrAfterNineAdvances() {
        // 2026-10-31 08:59 KST == 2026-10-30T23:59:00Z
        assertThat(resolverAt("2026-10-30T23:59:00Z")
                .resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31, null).firstRunDate())
                .isEqualTo(LocalDate.of(2026, 10, 31));
        // exactly 09:00 KST == 2026-10-31T00:00:00Z
        assertThat(resolverAt("2026-10-31T00:00:00Z")
                .resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31, null).firstRunDate())
                .isEqualTo(LocalDate.of(2026, 11, 30));
        // 09:30 KST
        assertThat(resolverAt("2026-10-31T00:30:00Z")
                .resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31, null).firstRunDate())
                .isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void weeklyBeforeNineKeepsToday_andAtNineAdvancesOneWeek() {
        assertThat(resolverAt("2026-10-18T23:00:00Z")
                .resolve(RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.MONDAY, null, null).firstRunDate())
                .isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(resolverAt("2026-10-19T00:00:00Z")
                .resolve(RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.MONDAY, null, null).firstRunDate())
                .isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void monthlyClampedDayInFebruaryIsKeptAsFirstRun() {
        // 2027-02-15 KST is before the clamped end-of-month default; resolver should pick Feb 28.
        assertThat(resolverAt("2027-02-15T00:00:00Z")
                .resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31, null).firstRunDate())
                .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void explicitFirstRunDate_mustBeFutureAndAligned() {
        var resolver = resolverAt("2026-10-13T00:00:00Z");
        assertThat(resolver.resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31))
                .firstRunAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));
        assertThatThrownBy(() -> resolver.resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                LocalDate.of(2026, 10, 30))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> resolver.resolve(RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                LocalDate.of(2026, 1, 31))).isInstanceOf(ApplicationException.class);
    }
}
