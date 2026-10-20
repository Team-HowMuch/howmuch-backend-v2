package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.account.model.AccountCandidate;
import com.amateur.howmuchbackend.application.port.out.account.BankAccountCandidateCachePort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BankAccountCandidateCacheRedisAdapter implements BankAccountCandidateCachePort {
    private static final String KEY_PREFIX = "account:connection-candidates:";
    private static final TypeReference<List<AccountCandidate>> CANDIDATE_LIST =
            new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<List<AccountCandidate>> find(Long userId, Long connectionId, String organization) {
        String value = redis.opsForValue().get(key(userId, connectionId, organization));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, CANDIDATE_LIST));
        } catch (Exception malformed) {
            log.warn("Ignoring malformed bank account candidate cache. userId={} connectionId={} organization={}",
                    userId, connectionId, organization);
            return Optional.empty();
        }
    }

    @Override
    public void save(
            Long userId,
            Long connectionId,
            String organization,
            List<AccountCandidate> candidates,
            Duration ttl
    ) {
        try {
            redis.opsForValue().set(
                    key(userId, connectionId, organization),
                    objectMapper.writeValueAsString(candidates),
                    ttl
            );
        } catch (Exception serializationFailure) {
            log.warn("Failed to cache bank account candidates. userId={} connectionId={} organization={}",
                    userId, connectionId, organization, serializationFailure);
        }
    }

    @Override
    public void evict(Long userId, Long connectionId, String organization) {
        redis.delete(key(userId, connectionId, organization));
    }

    private String key(Long userId, Long connectionId, String organization) {
        return KEY_PREFIX + userId + ":" + connectionId + ":" + organization;
    }
}
