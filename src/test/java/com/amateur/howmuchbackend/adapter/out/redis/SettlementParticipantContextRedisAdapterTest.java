package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementParticipantContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 링크 참여 문맥 Redis 저장")
class SettlementParticipantContextRedisAdapterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0);
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String LINK_TOKEN_HASH = "a".repeat(64);

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
    @DisplayName("링크와 참여자가 같은 유효 문맥을 원자 발급하고 재사용한다")
    void participantContext_sameRequest_issuedOrReused() throws Exception {
        UUID settlementId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        var storedContext = new SettlementParticipantContext(
                UUID.randomUUID(),
                LINK_TOKEN_HASH,
                settlementId,
                participantId,
                NOW,
                NOW.plus(TTL)
        );
        when(objectMapper.writeValueAsString(any())).thenReturn("candidate-context");
        when(redis.execute(
                org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                anyList(),
                eq("candidate-context"),
                eq(String.valueOf(TTL.toMillis()))
        )).thenReturn("stored-context");
        when(objectMapper.readValue(
                "stored-context",
                SettlementParticipantContext.class
        )).thenReturn(storedContext);

        var context = adapter.issue(
                LINK_TOKEN_HASH, settlementId, participantId, NOW
        );

        assertThat(context).isEqualTo(storedContext);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                keysCaptor.capture(),
                eq("candidate-context"),
                eq(String.valueOf(TTL.toMillis()))
        );
        assertThat(keysCaptor.getValue().get(0)).isEqualTo(
                SettlementParticipantContextRedisAdapter.INDEX_KEY_PREFIX
                        + LINK_TOKEN_HASH + ":" + participantId
        );
        assertThat(keysCaptor.getValue().get(1)).startsWith(
                SettlementParticipantContextRedisAdapter.KEY_PREFIX
        );
        verify(objectMapper).writeValueAsString(any());
        verify(objectMapper).readValue(
                "stored-context",
                SettlementParticipantContext.class
        );
    }

    @Test
    @DisplayName("문맥 토큰으로 저장된 참여 문맥을 조회한다")
    void loadsContextByToken() throws Exception {
        UUID contextToken = UUID.randomUUID();
        var storedContext = new SettlementParticipantContext(
                contextToken,
                LINK_TOKEN_HASH,
                UUID.randomUUID(),
                UUID.randomUUID(),
                NOW,
                NOW.plus(TTL)
        );
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(
                SettlementParticipantContextRedisAdapter.KEY_PREFIX + contextToken
        )).thenReturn("stored-context");
        when(objectMapper.readValue(
                "stored-context",
                SettlementParticipantContext.class
        )).thenReturn(storedContext);

        var context = adapter.findByToken(contextToken);

        assertThat(context).contains(storedContext);
        verify(valueOperations).get(
                SettlementParticipantContextRedisAdapter.KEY_PREFIX + contextToken
        );
    }

    @Test
    @DisplayName("문맥 토큰이 없거나 만료되어 Redis에 없으면 빈 결과를 반환한다")
    void returnsEmptyWhenContextDoesNotExist() {
        UUID contextToken = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(
                SettlementParticipantContextRedisAdapter.KEY_PREFIX + contextToken
        )).thenReturn(null);

        assertThat(adapter.findByToken(null)).isEmpty();
        assertThat(adapter.findByToken(contextToken)).isEmpty();
    }
}
