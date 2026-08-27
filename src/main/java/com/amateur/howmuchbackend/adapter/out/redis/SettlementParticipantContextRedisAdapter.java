package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementParticipantContextProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementParticipantContextRedisAdapter
        implements IssueSettlementParticipantContextPort,
        LoadSettlementParticipantContextPort {
    static final String KEY_PREFIX = "settlement:link-participant-context:";
    static final String INDEX_KEY_PREFIX = "settlement:link-participant-context-index:";
    private static final RedisScript<String> ISSUE_CONTEXT_SCRIPT = RedisScript.of("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return existing
            end

            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
            return ARGV[1]
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SettlementParticipantContextProperties properties;

    @Override
    public SettlementParticipantContext issue(
            String linkTokenHash,
            UUID settlementId,
            UUID participantId,
            LocalDateTime now
    ) {
        SettlementParticipantContext context = SettlementParticipantContext.issue(
                linkTokenHash,
                settlementId,
                participantId,
                now,
                now.plus(properties.ttl())
        );
        try {
            String serializedContext = objectMapper.writeValueAsString(context);
            String storedContext = redis.execute(
                    ISSUE_CONTEXT_SCRIPT,
                    List.of(indexKey(linkTokenHash, participantId), key(context.token())),
                    serializedContext,
                    String.valueOf(properties.ttl().toMillis())
            );
            if (storedContext == null) {
                throw new IllegalStateException(
                        "Unable to issue settlement participant context."
                );
            }
            return objectMapper.readValue(storedContext, SettlementParticipantContext.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Unable to serialize or deserialize settlement participant context.",
                    exception
            );
        }
    }

    @Override
    public Optional<SettlementParticipantContext> findByToken(UUID contextToken) {
        if (contextToken == null) {
            return Optional.empty();
        }
        String value = redis.opsForValue().get(key(contextToken.toString()));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    value,
                    SettlementParticipantContext.class
            ));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Unable to deserialize settlement participant context.",
                    exception
            );
        }
    }

    private String indexKey(String linkTokenHash, UUID participantId) {
        return INDEX_KEY_PREFIX + linkTokenHash + ":" + participantId;
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
