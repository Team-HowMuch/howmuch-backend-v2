package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SmsVerificationRedisAdapter implements SmsVerificationStorePort {
    private static final String KEY_PREFIX = "auth:sms:otp:";

    // The snapshot comparison protects every decision, including failures and request replacement.
    // Use Redis TTL at the linearization point; expiresAt belongs to the application host clock.
    private static final DefaultRedisScript<Long> TRANSITION = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then return -1 end
            if current ~= ARGV[1] then return 0 end
            if redis.call('PTTL', KEYS[1]) <= 0 then return -1 end
            redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL')
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public SmsOtpState.Outcome verifyAndConsume(String phoneHash, String requestId,
                                              String codeHash, int maxAttempts) {
        while (true) {
            String snapshot = redis.opsForValue().get(key(phoneHash));
            if (snapshot == null) return SmsOtpState.Outcome.EXPIRED;
            SmsOtpState state;
            try {
                state = readState(snapshot);
            } catch (JacksonException exception) {
                return SmsOtpState.Outcome.EXPIRED;
            }
            SmsOtpState.Verification verification = state.verify(requestId, codeHash, maxAttempts);
            String replacement = objectMapper.writeValueAsString(verification.state());
            Long result = redis.execute(TRANSITION, List.of(key(phoneHash)), snapshot, replacement);
            if (result == null) throw new IllegalStateException("Missing SMS verification transition result.");
            if (result == -1) return SmsOtpState.Outcome.EXPIRED;
            if (result == 1) return verification.outcome();
        }
    }

    @Override
    public Optional<SmsOtpState> findByPhoneHash(String phoneHash) {
        String value = redis.opsForValue().get(key(phoneHash));
        if (value == null) return Optional.empty();
        try {
            return Optional.of(readState(value));
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

    private SmsOtpState readState(String value) {
        // Jackson 3 rejects a missing primitive creator property; old OTPs have no consumed field.
        var tree = objectMapper.readTree(value);
        if (tree instanceof ObjectNode object && !object.has("consumed")) {
            object.put("consumed", false);
        }
        return objectMapper.treeToValue(tree, SmsOtpState.class);
    }

    private String key(String phoneHash) {
        return KEY_PREFIX + phoneHash;
    }
}
