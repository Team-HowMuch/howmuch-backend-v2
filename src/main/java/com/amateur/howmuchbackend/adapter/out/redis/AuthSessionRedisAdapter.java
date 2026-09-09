package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthSessionRedisAdapter implements AuthSessionPort {
    private static final String KEY_PREFIX = "auth:session:";

    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current or redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
            local ok, stored = pcall(cjson.decode, current)
            if not ok or type(stored) ~= 'table' then return 0 end
            local expected = cjson.decode(ARGV[1])
            if stored.sessionId ~= expected.sessionId
                    or stored.refreshToken ~= expected.refreshToken
                    or stored.expiresAt ~= expected.expiresAt then return 0 end
            local now = redis.call('TIME')
            local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            if tonumber(ARGV[3]) <= nowMillis then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PXAT', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<AuthSession> findByUserId(Long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(value, AuthSession.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean existsActiveByUserIdAndSessionId(Long userId, String sessionId, LocalDateTime now) {
        return findByUserId(userId)
                .filter(session -> session.sessionId().equals(sessionId))
                .filter(session -> !session.isExpired(now))
                .isPresent();
    }

    @Override
    public void save(AuthSession session) {
        Duration ttl = Duration.between(LocalDateTime.now(ZoneOffset.UTC), session.expiresAt());
        if (ttl.isZero() || ttl.isNegative()) {
            deleteByUserId(session.userId());
            return;
        }
        try {
            redis.opsForValue().set(
                    key(session.userId()),
                    objectMapper.writeValueAsString(session),
                    ttl
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize auth session.", exception);
        }
    }

    @Override
    public boolean rotate(AuthSession expected, AuthSession replacement) {
        if (!expected.userId().equals(replacement.userId())) {
            throw new IllegalArgumentException("Session rotation must keep the same user.");
        }
        long expiresAtMillis = replacement.expiresAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        try {
            return Long.valueOf(1).equals(redis.execute(ROTATE, List.of(key(expected.userId())),
                    objectMapper.writeValueAsString(expected),
                    objectMapper.writeValueAsString(replacement), Long.toString(expiresAtMillis)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize auth session.", exception);
        }
    }

    @Override
    public void deleteByUserId(Long userId) {
        redis.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
