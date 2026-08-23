package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.TrackSettlementContactMatchUsagePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementContactMatchUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementContactMatchUsageRedisAdapter implements TrackSettlementContactMatchUsagePort {
    static final String REQUEST_KEY_PREFIX = "settlement:contact-match:quota:requests:";
    static final String CONTACT_KEY_PREFIX = "settlement:contact-match:quota:contacts:";
    private static final RedisScript<String> RECORD_USAGE_SCRIPT = RedisScript.of("""
            local requestCount = redis.call('INCR', KEYS[1])
            if requestCount == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end

            local contactCount = redis.call('INCRBY', KEYS[2], ARGV[1])
            if contactCount == tonumber(ARGV[1]) then
                redis.call('PEXPIRE', KEYS[2], ARGV[3])
            end

            return tostring(requestCount) .. ':' .. tostring(contactCount)
            """, String.class);

    private final StringRedisTemplate redis;

    @Override
    public SettlementContactMatchUsage recordUsage(
            Long userId,
            int contactCount,
            Duration requestWindow,
            Duration contactWindow
    ) {
        if (userId == null || contactCount < 1) {
            throw new IllegalArgumentException("연락처 매칭 사용량 식별자와 개수는 필수입니다.");
        }
        requirePositive(requestWindow, "요청 quota 기간");
        requirePositive(contactWindow, "연락처 quota 기간");

        String result = redis.execute(
                RECORD_USAGE_SCRIPT,
                List.of(REQUEST_KEY_PREFIX + userId, CONTACT_KEY_PREFIX + userId),
                String.valueOf(contactCount),
                String.valueOf(requestWindow.toMillis()),
                String.valueOf(contactWindow.toMillis())
        );
        if (result == null) {
            throw new IllegalStateException("연락처 매칭 사용량을 기록할 수 없습니다.");
        }

        String[] counts = result.split(":", -1);
        if (counts.length != 2) {
            throw new IllegalStateException("연락처 매칭 사용량 응답이 올바르지 않습니다.");
        }
        try {
            return new SettlementContactMatchUsage(
                    Long.parseLong(counts[0]),
                    Long.parseLong(counts[1])
            );
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("연락처 매칭 사용량 응답이 올바르지 않습니다.", exception);
        }
    }

    private void requirePositive(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다.");
        }
    }
}
