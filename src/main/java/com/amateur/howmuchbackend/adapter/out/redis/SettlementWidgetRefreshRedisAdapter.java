package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetRefreshStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SettlementWidgetRefreshRedisAdapter implements SettlementWidgetRefreshStorePort {
    private static final long LEASE_MILLIS = 600_000;
    private static final long COOLDOWN_MILLIS = 60_000;
    private static final long HISTORY_MILLIS = 2_592_000_000L;
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if ARGV[2] ~= '' then redis.call('SET', KEYS[3], ARGV[2], 'PX', ARGV[4]) end
            redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    @Override
    public boolean tryAcquire(Long userId, String owner) {
        return Long.valueOf(1).equals(redis.execute(ACQUIRE, keys(userId).subList(0, 2),
                owner, Long.toString(LEASE_MILLIS), Long.toString(COOLDOWN_MILLIS)));
    }

    @Override
    public boolean renew(Long userId, String owner) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(keys(userId).getFirst()),
                owner, Long.toString(LEASE_MILLIS)));
    }

    @Override
    public void finish(Long userId, String owner, LocalDateTime successfulReconciledAt) {
        redis.execute(FINISH, keys(userId), owner,
                successfulReconciledAt == null ? "" : successfulReconciledAt.toString(),
                Long.toString(COOLDOWN_MILLIS), Long.toString(HISTORY_MILLIS));
    }

    @Override
    public Optional<LocalDateTime> lastReconciledAt(Long userId) {
        return Optional.ofNullable(redis.opsForValue().get(keys(userId).get(2))).map(LocalDateTime::parse);
    }

    private List<String> keys(Long userId) {
        String prefix = "settlement:widget:{" + userId + "}:";
        return List.of(prefix + "lock", prefix + "cooldown", prefix + "last-reconciled");
    }
}
