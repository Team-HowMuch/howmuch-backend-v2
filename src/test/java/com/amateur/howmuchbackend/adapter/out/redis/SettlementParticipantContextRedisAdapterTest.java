package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.infra.settlement.config.SettlementParticipantContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 링크 참여 문맥 Redis 저장")
class SettlementParticipantContextRedisAdapterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0);
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ObjectMapper objectMapper;

    private SettlementParticipantContextRedisAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SettlementParticipantContextRedisAdapter(
                redis,
                objectMapper,
                new SettlementParticipantContextProperties(TTL)
        );
    }

    @Test
    @DisplayName("context UUID key에 링크·정산·참여자 문맥을 설정된 TTL로 저장한다")
    void storesContextWithTtl() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenReturn("serialized-context");

        var context = adapter.issue(
                "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), NOW
        );

        assertThat(context.expiresAt()).isEqualTo(NOW.plus(TTL));
        verify(valueOperations).set(
                SettlementParticipantContextRedisAdapter.KEY_PREFIX + context.token(),
                "serialized-context",
                TTL
        );
        verify(objectMapper).writeValueAsString(org.mockito.ArgumentMatchers.any());
    }
}
