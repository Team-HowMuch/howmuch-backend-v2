package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SmsVerificationRedisAdapter implements SmsVerificationStorePort {
    private static final String KEY_PREFIX = "auth:sms:otp:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<SmsOtpState> findByPhoneHash(String phoneHash) {
        String value = redis.opsForValue().get(key(phoneHash));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(value, SmsOtpState.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void save(String phoneHash, SmsOtpState state, Duration ttl) {
        try {
            redis.opsForValue().set(key(phoneHash), objectMapper.writeValueAsString(state), ttl);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize SMS verification state.", exception);
        }
    }

    @Override
    public void deleteByPhoneHash(String phoneHash) {
        redis.delete(key(phoneHash));
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
