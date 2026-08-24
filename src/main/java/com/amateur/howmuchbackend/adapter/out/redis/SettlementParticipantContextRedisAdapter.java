package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementParticipantContextProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementParticipantContextRedisAdapter
        implements IssueSettlementParticipantContextPort {
    static final String KEY_PREFIX = "settlement:link-participant-context:";

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
            redis.opsForValue().set(
                    KEY_PREFIX + context.token(),
                    objectMapper.writeValueAsString(context),
                    properties.ttl()
            );
            return context;
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Unable to serialize settlement participant context.",
                    exception
            );
        }
    }
}
