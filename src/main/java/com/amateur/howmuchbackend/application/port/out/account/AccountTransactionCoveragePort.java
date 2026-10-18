package com.amateur.howmuchbackend.application.port.out.account;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Records when an account-month range was last collected from CODEF so other domains can reuse
 * stored transactions and clients can tell "never fetched" apart from "fetched with zero rows".
 *
 * <p>This is a best-effort hint stored in Redis. It is not the source of truth for transactions;
 * an evicted or expired entry only causes a safe refetch.
 */
public interface AccountTransactionCoveragePort {
    Optional<LocalDateTime> findCoveredAt(Long accountId, YearMonth yearMonth);

    /** True only for a fully collected, closed calendar month committed to PostgreSQL. */
    boolean coversMonth(Long accountId, YearMonth yearMonth);

    /** A committed snapshot whose exact dates include this request and whose age is within maxAge. */
    boolean recentlyCovers(Long accountId, YearMonth yearMonth, LocalDate from, LocalDate through,
            LocalDateTime now, Duration maxAge);

    void markCovered(Long accountId, YearMonth yearMonth, LocalDate from, LocalDate through,
            LocalDateTime fetchedAt, Duration ttl);
}
