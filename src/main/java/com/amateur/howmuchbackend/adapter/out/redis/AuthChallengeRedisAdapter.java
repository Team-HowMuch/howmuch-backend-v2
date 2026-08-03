package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthChallengeRedisAdapter implements AuthChallengeStorePort {
    private static final String KEY_PREFIX = "auth:challenge:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<AuthChallenge> findById(String challengeId) {
        String value = redis.opsForValue().get(key(challengeId));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(value, AuthChallenge.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void save(AuthChallenge challenge, Duration ttl) {
        try {
            redis.opsForValue().set(key(challenge.challengeId()), objectMapper.writeValueAsString(challenge), ttl);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize auth challenge.", exception);
        }
    }

    @Override
    public void deleteById(String challengeId) {
        redis.delete(key(challengeId));
    }

    private String key(String challengeId) {
        return KEY_PREFIX + challengeId;
    }
}
