package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@Slf4j
public class NettingEstimateCacheRedisAdapter implements NettingEstimateCachePort {
    private static final DefaultRedisScript<String> CLAIM =
            new DefaultRedisScript<>(
                    """
if redis.call('EXISTS',KEYS[1])==1 or redis.call('EXISTS',KEYS[2])==1 then return nil end
redis.call('SET',KEYS[2],ARGV[1],'PX',ARGV[2])
return redis.call('GET',KEYS[3]) or '0'
""",
                    String.class);
    private static final DefaultRedisScript<Long> PUBLISH =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end
                    if (redis.call('GET',KEYS[3]) or '0')~=ARGV[2] then return 0 end
                    redis.call('SET',KEYS[1],ARGV[3],'PX',ARGV[4])
                    redis.call('DEL',KEYS[2])
                    return 1
                    """,
                    Long.class);
    private static final DefaultRedisScript<Long> RELEASE =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
                    return redis.call('DEL',KEYS[1])
                    """,
                    Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE =
            new DefaultRedisScript<>(
                    """
                    redis.call('SET',KEYS[3],ARGV[1],'PX',ARGV[2])
                    redis.call('DEL',KEYS[1],KEYS[2])
                    return 1
                    """,
                    Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl, lease;

    public NettingEstimateCacheRedisAdapter(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            @Value("${howmuch.settlement.netting.estimate-cache.ttl:30s}") Duration ttl,
            @Value("${howmuch.settlement.netting.estimate-cache.lease:10s}") Duration lease) {
        if (ttl.toMillis() <= 0 || lease.toMillis() <= 0)
            throw new IllegalArgumentException("Estimate cache durations must be positive");
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.lease = lease;
    }

    @Override
    public Optional<Counts> find(long groupId) {
        return available(
                () -> {
                    var raw = redis.opsForValue().get(keys(groupId).getFirst());
                    if (raw == null) return Optional.empty();
                    try {
                        var counts = mapper.readValue(raw, Counts.class);
                        if (counts == null) invalidate(groupId);
                        return Optional.ofNullable(counts);
                    } catch (tools.jackson.core.JacksonException
                            | IllegalArgumentException invalid) {
                        invalidate(groupId);
                        return Optional.empty();
                    }
                });
    }

    @Override
    public Optional<Claim> tryClaim(long groupId) {
        return available(
                () -> {
                    var token = UUID.randomUUID().toString();
                    var revision =
                            redis.execute(
                                    CLAIM, keys(groupId), token, Long.toString(lease.toMillis()));
                    return revision == null
                            ? Optional.empty()
                            : Optional.of(new Claim(token, revision));
                });
    }

    @Override
    public boolean publish(long groupId, Claim claim, Counts counts) {
        return available(
                () ->
                        Long.valueOf(1)
                                .equals(
                                        redis.execute(
                                                PUBLISH,
                                                keys(groupId),
                                                claim.token(),
                                                claim.revision(),
                                                mapper.writeValueAsString(counts),
                                                Long.toString(ttl.toMillis()))));
    }

    @Override
    public void release(long groupId, Claim claim) {
        try {
            redis.execute(RELEASE, List.of(keys(groupId).get(1)), claim.token());
        } catch (DataAccessException failure) {
            log.warn(
                    "Estimate claim release failed, groupId={}, cause={}",
                    groupId,
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public void invalidate(long groupId) {
        try {
            redis.execute(
                    INVALIDATE,
                    keys(groupId),
                    UUID.randomUUID().toString(),
                    Long.toString(ttl.plus(lease).plusSeconds(5).toMillis()));
        } catch (DataAccessException failure) {
            log.warn(
                    "Estimate invalidation failed after database commit, groupId={}, cause={}",
                    groupId,
                    failure.getClass().getSimpleName());
        }
    }

    private <T> T available(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException failure) {
            throw ApplicationException.of(NettingStatusCode.NETTING_ESTIMATE_UNAVAILABLE);
        }
    }

    private List<String> keys(long groupId) {
        String prefix = "settlement:netting-estimate:v2:{" + groupId + "}:";
        return List.of(prefix + "data", prefix + "lock", prefix + "revision");
    }
}
