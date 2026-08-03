package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthSessionRedisAdapter implements AuthSessionPort {
    private static final String KEY_PREFIX = "auth:session:";

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
    public void deleteByUserId(Long userId) {
        redis.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
