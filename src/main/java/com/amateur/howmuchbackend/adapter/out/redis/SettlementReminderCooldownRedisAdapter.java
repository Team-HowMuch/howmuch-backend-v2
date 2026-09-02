package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class SettlementReminderCooldownRedisAdapter implements SettlementReminderCooldownPort {
    static final String KEY_PREFIX = "settlement:reminder:";
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean reserve(
            UUID settlementId,
            UUID participantId,
            String reservationId,
            Duration cooldown
    ) {
        Boolean reserved = redis.opsForValue().setIfAbsent(
                key(settlementId, participantId),
                reservationId,
                cooldown
        );
        return Boolean.TRUE.equals(reserved);
    }

    @Override
    public void release(UUID settlementId, UUID participantId, String reservationId) {
        redis.execute(
                RELEASE_SCRIPT,
                List.of(key(settlementId, participantId)),
                reservationId
        );
    }

    @Override
    public Optional<Duration> findRemainingCooldown(
            UUID settlementId,
            UUID participantId
    ) {
        Long remainingMillis = redis.getExpire(
                key(settlementId, participantId),
                TimeUnit.MILLISECONDS
        );
        if (remainingMillis == null || remainingMillis <= 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(remainingMillis));
    }

    private String key(UUID settlementId, UUID participantId) {
        return KEY_PREFIX + settlementId + ":" + participantId;
    }
}
