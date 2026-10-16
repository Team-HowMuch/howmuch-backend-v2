package com.amateur.howmuchbackend.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

class NettingEstimateCacheRedisAdapterTest {
    @Test
    void redisFailureDoesNotFailCommittedWritesOrTriggerUnboundedRecalculation() {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        var adapter =
                new NettingEstimateCacheRedisAdapter(
                        redis, new ObjectMapper(), Duration.ofSeconds(30), Duration.ofSeconds(10));
        when(redis.opsForValue().get(anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        assertThatThrownBy(() -> adapter.find(1))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_ESTIMATE_UNAVAILABLE));
        doThrow(new RedisConnectionFailureException("offline"))
                .when(redis)
                .execute(
                        any(org.springframework.data.redis.core.script.RedisScript.class),
                        anyList(),
                        any(Object[].class));
        assertThatCode(() -> adapter.invalidate(1)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.release(1, new NettingEstimateCachePort.Claim("token", "0")))
                .doesNotThrowAnyException();
    }
}
