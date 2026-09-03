package com.amateur.howmuchbackend.application.port.out.account;

import java.time.Duration;
import java.time.YearMonth;

public interface AccountTransactionRefreshStorePort {
    boolean isFresh(Long accountId, YearMonth yearMonth);

    void markFresh(Long accountId, YearMonth yearMonth, Duration ttl);
}
