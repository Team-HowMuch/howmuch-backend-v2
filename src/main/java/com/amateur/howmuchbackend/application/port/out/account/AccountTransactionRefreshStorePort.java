package com.amateur.howmuchbackend.application.port.out.account;

import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;

public interface AccountTransactionRefreshStorePort {
    boolean isSyncInProgress(Long accountId, YearMonth yearMonth);

    boolean isFresh(Long accountId, YearMonth yearMonth);

    void markFresh(Long accountId, YearMonth yearMonth, Duration ttl);

    /**
     * Attempts to become the single writer for the account-month synchronization.
     *
     * @return the claim token when acquired, or empty when another synchronization already holds it.
     */
    Optional<String> tryClaim(Long accountId, YearMonth yearMonth, Duration ttl);

    /**
     * Releases the claim only when the token still matches, so a late caller cannot release a newer
     * claim.
     */
    void release(Long accountId, YearMonth yearMonth, String token);
}
