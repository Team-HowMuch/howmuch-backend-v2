package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.LoginAttemptStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LoginAttemptRedisAdapter implements LoginAttemptStorePort {
    private static final String KEY_PREFIX = "auth:login:attempt:";
    private static final RedisScript<Long> INCREASE_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 or count == tonumber(ARGV[2]) then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    @Override
    public long getFailureCount(String phoneHash) {
        String value = redis.opsForValue().get(key(phoneHash));
        return value == null ? 0 : Long.parseLong(value);
    }

    @Override
    public long increaseFailureCount(String phoneHash, int maxAttempts, Duration lockDuration) {
        Long count = redis.execute(
                INCREASE_SCRIPT,
                List.of(key(phoneHash)),
                String.valueOf(lockDuration.toMillis()),
                String.valueOf(maxAttempts)
        );
        if (count == null) throw new IllegalStateException("Unable to increase login failure count.");
        return count;
    }

    @Override
    public void deleteByPhoneHash(String phoneHash) {
        redis.delete(key(phoneHash));
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
