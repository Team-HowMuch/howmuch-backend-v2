package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import lombok.RequiredArgsConstructor;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementBankLookupBudgetProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementBankLookupBudgetRedisAdapter implements SettlementBankLookupBudgetPort {
    private final StringRedisTemplate redis;
    private final SettlementBankLookupBudgetProperties properties;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            for _, key in ipairs(KEYS) do
                local total = tonumber(redis.call('HGET', key, 'total') or '0')
                local automatic = tonumber(redis.call('HGET', key, 'automatic') or '0')
                if total >= tonumber(ARGV[3]) or (ARGV[1] == '1' and automatic >= tonumber(ARGV[4])) then return 0 end
            end
            for _, key in ipairs(KEYS) do
                redis.call('HINCRBY', key, 'total', 1)
                if ARGV[1] == '1' then redis.call('HINCRBY', key, 'automatic', 1) end
                redis.call('EXPIRE', key, ARGV[2])
            end
            return 1
            """, Long.class);

    @Override
    public void reserve(Long userId, boolean automatic, LocalDateTime now, int totalLimit, int automaticLimit) {
        var date = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).toLocalDate();
        var prefix = "settlement:bank-budget:{" + properties.namespace() + "}:";
        var keys = List.of(prefix + "global:" + date, prefix + "user:" + userId + ":" + date);
        long expiry = date.plusDays(1).atStartOfDay(SEOUL).plusHours(1).toEpochSecond() - now.toEpochSecond(ZoneOffset.UTC);
        final Long acquired;
        try {
            acquired = redis.execute(RESERVE, keys, automatic ? "1" : "0", Long.toString(expiry), Integer.toString(totalLimit), Integer.toString(automaticLimit));
        } catch (DataAccessException error) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE, error);
        }
        if (!Long.valueOf(1).equals(acquired)) throw ApplicationException.of(CommonStatusCode.TOO_MANY_REQUEST);
    }
}
