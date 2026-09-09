package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.auth.model.LoginAttempt;
import com.amateur.howmuchbackend.application.port.out.auth.LoginAttemptStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LoginAttemptRedisAdapter implements LoginAttemptStorePort {
    private static final String KEY_PREFIX = "auth:login:attempt:";
    private static final RedisScript<String> RESERVE_SCRIPT = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            local generation = ARGV[3]
            local count = 0
            local ttl = tonumber(ARGV[2])
            if value then
                ttl = redis.call('PTTL', KEYS[1])
                if ttl <= 0 then return '' end
                local storedGeneration, storedCount = string.match(value, '^([^:]+):(%d+)$')
                if storedGeneration then
                    generation = storedGeneration
                    count = tonumber(storedCount)
                else
                    -- Retain the count and remaining TTL of pre-reservation deployments.
                    if not string.match(value, '^%d+$') then return '' end
                    count = tonumber(value)
                end
            end
            if count >= tonumber(ARGV[1]) then return '' end
            count = count + 1
            if count == 1 or count == tonumber(ARGV[1]) then ttl = tonumber(ARGV[2]) end
            local reserved = generation .. ':' .. count
            redis.call('SET', KEYS[1], reserved, 'PX', ttl)
            return reserved
            """, String.class);
    private static final RedisScript<Long> RESET_SCRIPT = RedisScript.of("""
            local value = redis.call('GET', KEYS[1])
            if value and string.match(value, '^([^:]+):%d+$') == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public Optional<LoginAttempt> reserve(String phoneHash, int maxAttempts, Duration lockDuration) {
        String reserved = redis.execute(RESERVE_SCRIPT, List.of(key(phoneHash)),
                String.valueOf(maxAttempts), String.valueOf(lockDuration.toMillis()), UUID.randomUUID().toString());
        if (reserved == null) throw new IllegalStateException("Unable to reserve login attempt.");
        if (reserved.isEmpty()) return Optional.empty();
        String[] parts = reserved.split(":", 2);
        return Optional.of(new LoginAttempt(parts[0], Integer.parseInt(parts[1])));
    }

    @Override
    public void resetAfterSuccess(String phoneHash, String generation) {
        Long result = redis.execute(RESET_SCRIPT, List.of(key(phoneHash)), generation);
        if (result == null) throw new IllegalStateException("Unable to reset login attempts.");
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
