package com.amateur.howmuchbackend.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AccountTransactionCoverageRedisAdapterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AccountTransactionCoverageRedisAdapter adapter = new AccountTransactionCoverageRedisAdapter(redis);
    private final YearMonth month = YearMonth.of(2026, 8);
    private final String key = "account:transaction-coverage:v2:1:2026-08";

    private void value(String from, String through, String instant) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key)).thenReturn(Instant.parse(instant).toEpochMilli() + "|" + from + "|" + through);
    }

    @Test void partialRangeIsNotWholeMonth() {
        value("2026-08-20", "2026-08-31", "2026-09-01T00:00:00Z");
        assertThat(adapter.findCoveredAt(1L, month)).isPresent();
        assertThat(adapter.coversMonth(1L, month)).isFalse();
    }

    @Test void currentMonthSnapshotDoesNotBecomeCompleteWhenCalendarAdvances() {
        value("2026-08-01", "2026-08-18", "2026-08-18T03:00:00Z");
        assertThat(adapter.coversMonth(1L, month)).isFalse();
    }

    @Test void lastDayCollectionDoesNotIncludeFutureTransactionsOnThatDay() {
        value("2026-08-01", "2026-08-31", "2026-08-31T14:59:59Z");
        assertThat(adapter.coversMonth(1L, month)).isFalse();
    }

    @Test void closedMonthIncludingZeroTransactionsCanBeCovered() {
        value("2026-08-01", "2026-08-31", "2026-08-31T15:00:00Z");
        assertThat(adapter.coversMonth(1L, month)).isTrue();
    }

    @Test void legacyAndMalformedMarkersCannotAssertCoverage() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key)).thenReturn(null, "1790000000000", "bad|date|date");
        assertThat(adapter.coversMonth(1L, month)).isFalse();
        assertThat(adapter.coversMonth(1L, month)).isFalse();
        assertThat(adapter.findCoveredAt(1L, month)).isEmpty();
        verify(values, never()).get("account:transaction-coverage:1:2026-08");
    }

    @Test void recentSnapshotRequiresDatesAndFreshCommitWithoutFutureTimestamp() {
        value("2026-08-01", "2026-08-18", "2026-08-18T03:00:00Z");
        LocalDateTime at = LocalDateTime.of(2026, 8, 18, 3, 0);
        assertThat(adapter.recentlyCovers(1L, month, month.atDay(10), month.atDay(18), at.plusSeconds(59), Duration.ofMinutes(1))).isTrue();
        assertThat(adapter.recentlyCovers(1L, month, month.atDay(10), month.atDay(19), at.plusSeconds(59), Duration.ofMinutes(1))).isFalse();
        assertThat(adapter.recentlyCovers(1L, month, month.atDay(10), month.atDay(18), at.plusMinutes(1), Duration.ofMinutes(1))).isFalse();
        assertThat(adapter.recentlyCovers(1L, month, month.atDay(10), month.atDay(18), at.minusSeconds(1), Duration.ofMinutes(1))).isFalse();
        assertThat(adapter.coversMonth(1L, month)).isFalse();
    }

    @Test void markerPersistsExactDates() {
        when(redis.opsForValue()).thenReturn(values);
        LocalDateTime at = LocalDateTime.of(2026, 9, 1, 0, 0);
        adapter.markCovered(1L, month, month.atDay(1), month.atEndOfMonth(), at, Duration.ofDays(30));
        verify(values).set(key, at.toInstant(ZoneOffset.UTC).toEpochMilli() + "|2026-08-01|2026-08-31", Duration.ofDays(30));
    }
}
