package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionCoveragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountTransactionCoverageRedisAdapter implements AccountTransactionCoveragePort {
    private static final String KEY_PREFIX = "account:transaction-coverage:v2:";

    private final StringRedisTemplate redis;

    @Override
    public Optional<LocalDateTime> findCoveredAt(Long accountId, YearMonth yearMonth) {
        return read(accountId, yearMonth).map(Coverage::fetchedAt);
    }

    @Override
    public boolean coversMonth(Long accountId, YearMonth yearMonth) {
        return read(accountId, yearMonth).filter(value ->
                !value.from().isAfter(yearMonth.atDay(1))
                        && !value.through().isBefore(yearMonth.atEndOfMonth())
                        // A collection on the last day cannot cover transactions later that day.
                        && value.fetchedAt().atOffset(ZoneOffset.UTC)
                                .atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate()
                                .isAfter(yearMonth.atEndOfMonth())).isPresent();
    }

    @Override
    public boolean recentlyCovers(Long accountId, YearMonth yearMonth, LocalDate from, LocalDate through,
            LocalDateTime now, Duration maxAge) {
        return read(accountId, yearMonth).filter(value ->
                !from.isAfter(through)
                        && !value.from().isAfter(from) && !value.through().isBefore(through)
                        && !value.fetchedAt().isAfter(now)
                        && value.fetchedAt().plus(maxAge).isAfter(now)).isPresent();
    }

    private Optional<Coverage> read(Long accountId, YearMonth yearMonth) {
        String value = redis.opsForValue().get(key(accountId, yearMonth));
        if (value == null) return Optional.empty();
        try {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) return Optional.empty();
            Coverage coverage = new Coverage(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(parts[0])), ZoneOffset.UTC),
                    LocalDate.parse(parts[1]), LocalDate.parse(parts[2]));
            if (coverage.from().isAfter(coverage.through())
                    || !YearMonth.from(coverage.from()).equals(yearMonth)
                    || !YearMonth.from(coverage.through()).equals(yearMonth)) return Optional.empty();
            return Optional.of(coverage);
        } catch (RuntimeException malformed) {
            log.warn("Ignoring malformed account transaction coverage. accountId={} month={}", accountId, yearMonth);
            return Optional.empty();
        }
    }

    @Override
    public void markCovered(Long accountId, YearMonth yearMonth, LocalDate from, LocalDate through,
            LocalDateTime fetchedAt, Duration ttl) {
        long epochMillis = fetchedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        redis.opsForValue().set(key(accountId, yearMonth), epochMillis + "|" + from + "|" + through, ttl);
    }

    private record Coverage(LocalDateTime fetchedAt, LocalDate from, LocalDate through) {}

    private String key(Long accountId, YearMonth yearMonth) {
        return KEY_PREFIX + accountId + ":" + yearMonth;
    }
}
