package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.auth.PhoneNumberRegistrationCheckLimitPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PhoneNumberRegistrationCheckLimitRedisAdapter implements PhoneNumberRegistrationCheckLimitPort {
    private static final String ADDRESS_KEY_PREFIX = "auth:phone-number-check:address:";
    private static final String PHONE_KEY_PREFIX = "auth:phone-number-check:phone:";
    private static final RedisScript<Long> TRY_CONSUME = RedisScript.of(
            new ClassPathResource("redis/phone-number-registration-check-try-consume.lua"),
            Long.class
    );

    private final StringRedisTemplate redis;

    @Override
    public boolean tryConsume(
            String clientAddress,
            String phoneHash,
            int maxAddressRequests,
            int maxPhoneRequests,
            Duration window
    ) {
        long windowMillis = window.toMillis();
        if (clientAddress == null || clientAddress.isBlank() || phoneHash == null || phoneHash.isBlank()) {
            throw new IllegalArgumentException("Phone number check limit identifiers must not be blank.");
        }
        if (maxAddressRequests <= 0 || maxPhoneRequests <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("Phone number check limits must be positive.");
        }

        Long result;
        try {
            result = redis.execute(
                    TRY_CONSUME,
                    List.of(ADDRESS_KEY_PREFIX + clientAddress, PHONE_KEY_PREFIX + phoneHash),
                    Integer.toString(maxAddressRequests),
                    Integer.toString(maxPhoneRequests),
                    Long.toString(windowMillis)
            );
        } catch (DataAccessException exception) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE, exception);
        }
        if (result == null || result < 0) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
        }
        return result == 1;
    }
}
