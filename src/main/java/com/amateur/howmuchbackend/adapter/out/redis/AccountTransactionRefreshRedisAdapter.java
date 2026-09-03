package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.YearMonth;

@Component
@RequiredArgsConstructor
public class AccountTransactionRefreshRedisAdapter implements AccountTransactionRefreshStorePort {
    private static final String KEY_PREFIX = "account:transaction-refresh:";

    private final StringRedisTemplate redis;

    @Override
    public boolean isFresh(Long accountId, YearMonth yearMonth) {
        return Boolean.TRUE.equals(redis.hasKey(key(accountId, yearMonth)));
    }

    @Override
    public void markFresh(Long accountId, YearMonth yearMonth, Duration ttl) {
        redis.opsForValue().set(key(accountId, yearMonth), "1", ttl);
    }

    private String key(Long accountId, YearMonth yearMonth) {
        return KEY_PREFIX + accountId + ":" + yearMonth;
    }
}
