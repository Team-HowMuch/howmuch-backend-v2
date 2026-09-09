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

@Component
@RequiredArgsConstructor
public class SmsVerificationRedisAdapter implements SmsVerificationStorePort {
    private static final String KEY_PREFIX = "auth:sms:otp:";
    private static final String COOLDOWN_PREFIX = "auth:sms:cooldown:";

    // Compare the legacy snapshot before interpreting its remaining TTL. Both writes are atomic.
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
            local current = redis.call('GET', KEYS[1])
            if (current or '') ~= ARGV[1] then return 0 end
            if current then
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl == -1 then return -1 end
                local remaining = ttl - tonumber(ARGV[2])
                if remaining > 0 then
                    redis.call('SET', KEYS[2], ARGV[3], 'PX', remaining)
                    return -1
                end
            end
            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[6])
            redis.call('SET', KEYS[1], ARGV[4], 'PX', ARGV[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DISCARD = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            if cjson.decode(current).verificationRequestId ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

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
    public boolean tryReserve(String phoneHash, SmsOtpState state, Duration otpTtl, Duration cooldown) {
        long otpMillis = otpTtl.toMillis();
        long cooldownMillis = cooldown.toMillis();
        if (otpMillis <= 0 || cooldownMillis <= 0) {
            throw new IllegalArgumentException("SMS lifetimes must be positive milliseconds.");
        }
        String replacement = objectMapper.writeValueAsString(state);
        while (true) {
            String snapshot = redis.opsForValue().get(key(phoneHash));
            long legacyGapMillis = 0;
            if (snapshot != null) {
                SmsOtpState existing = readState(snapshot);
                // Timestamp difference is a duration; never compare clocks across application hosts.
                legacyGapMillis = Duration.between(existing.resendAvailableAt(), existing.expiresAt()).toMillis();
            }
            Long result = redis.execute(RESERVE, List.of(key(phoneHash), COOLDOWN_PREFIX + phoneHash),
                    snapshot == null ? "" : snapshot, Long.toString(legacyGapMillis),
                    state.verificationRequestId(), replacement,
                    Long.toString(otpMillis), Long.toString(cooldownMillis));
            if (result == null) throw new IllegalStateException("Missing SMS reservation result.");
            if (result == -1) return false;
            if (result == 1) return true;
        }
    }

    @Override
    public void discardRequest(String phoneHash, String requestId) {
        Long result = redis.execute(DISCARD, List.of(key(phoneHash)), requestId);
        if (result == null) throw new IllegalStateException("Missing SMS discard result.");
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
