package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionRefreshStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AccountTransactionRefreshRedisAdapter implements AccountTransactionRefreshStorePort {
    private static final String KEY_PREFIX = "account:transaction-refresh:v2:";
    private static final String CLAIM_KEY_PREFIX = "account:transaction-sync-claim:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean isSyncInProgress(Long accountId, YearMonth yearMonth) {
        return Boolean.TRUE.equals(redis.hasKey(claimKey(accountId, yearMonth)));
    }

    @Override
    public boolean isFresh(Long accountId, YearMonth yearMonth) {
        return Boolean.TRUE.equals(redis.hasKey(key(accountId, yearMonth)));
    }

    @Override
    public void markFresh(Long accountId, YearMonth yearMonth, Duration ttl) {
        redis.opsForValue().set(key(accountId, yearMonth), "1", ttl);
    }

    @Override
    public Optional<String> tryClaim(Long accountId, YearMonth yearMonth, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
                claimKey(accountId, yearMonth), token, ttl
        );
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void release(Long accountId, YearMonth yearMonth, String token) {
        redis.execute(
                RELEASE_SCRIPT,
                List.of(claimKey(accountId, yearMonth)),
                token
        );
    }

    private String key(Long accountId, YearMonth yearMonth) {
        return KEY_PREFIX + accountId + ":" + yearMonth;
    }

    private String claimKey(Long accountId, YearMonth yearMonth) {
        return CLAIM_KEY_PREFIX + accountId + ":" + yearMonth;
    }
}
