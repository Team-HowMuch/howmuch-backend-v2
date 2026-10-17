package com.amateur.howmuchbackend.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
class NettingEstimateCacheRedisAdapterIntegrationTest {
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;

    private long group() {
        return java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private NettingEstimateCacheRedisAdapter adapter(Duration ttl, Duration lease) {
        return new NettingEstimateCacheRedisAdapter(redis, mapper, ttl, lease);
    }

    @Test
    void roundTripExpiresWithoutExtendingTtlOnRead() {
        var adapter = adapter(Duration.ofMillis(300), Duration.ofSeconds(1));
        long g = group();
        var counts = new NettingEstimateCachePort.Counts(2, 2, 1, 2);
        var claim = adapter.tryClaim(g).orElseThrow();
        assertThat(adapter.tryClaim(g)).isEmpty();
        assertThat(adapter.publish(g, claim, counts)).isTrue();
        assertThat(adapter.find(g)).contains(counts);
        await().atMost(Duration.ofSeconds(2)).until(() -> adapter.find(g).isEmpty());
    }

    @Test
    void invalidationFencesOldPublishAndOldReleaseCannotDeleteNewClaim() {
        var adapter = adapter(Duration.ofSeconds(1), Duration.ofSeconds(1));
        long g = group();
        var old = adapter.tryClaim(g).orElseThrow();
        adapter.invalidate(g);
        var next = adapter.tryClaim(g).orElseThrow();
        assertThat(next.revision()).isNotEqualTo(old.revision());
        assertThat(adapter.publish(g, old, new NettingEstimateCachePort.Counts(2, 2, 1, 2)))
                .isFalse();
        adapter.release(g, old);
        assertThat(adapter.tryClaim(g)).isEmpty();
        assertThat(adapter.publish(g, next, new NettingEstimateCachePort.Counts(1, 1, 1, 1)))
                .isTrue();
        assertThat(adapter.find(g)).contains(new NettingEstimateCachePort.Counts(1, 1, 1, 1));
    }

    @Test
    void expiredClaimCannotPublishOrReleaseAnotherWorkersClaim() {
        var adapter = adapter(Duration.ofSeconds(1), Duration.ofMillis(80));
        long g = group();
        var old = adapter.tryClaim(g).orElseThrow();
        var recovered = new AtomicReference<NettingEstimateCachePort.Claim>();
        await().pollInterval(Duration.ofMillis(10))
                .atMost(Duration.ofSeconds(2))
                .until(
                        () -> {
                            adapter(Duration.ofSeconds(1), Duration.ofSeconds(1))
                                    .tryClaim(g)
                                    .ifPresent(recovered::set);
                            return recovered.get() != null;
                        });
        assertThat(adapter.publish(g, old, new NettingEstimateCachePort.Counts(2, 2, 1, 2)))
                .isFalse();
        adapter.release(g, old);
        assertThat(
                        adapter.publish(
                                g,
                                recovered.get(),
                                new NettingEstimateCachePort.Counts(1, 1, 1, 1)))
                .isTrue();
    }

    @Test
    void corruptCachedCountsAreEvictedAndCanBeRecomputed() {
        long g = group();
        var adapter = adapter(Duration.ofSeconds(1), Duration.ofSeconds(1));
        redis.opsForValue()
                .set(
                        "settlement:netting-estimate:v2:{" + g + "}:data",
                        "invalid-json",
                        Duration.ofSeconds(1));
        assertThat(adapter.find(g)).isEmpty();
        assertThat(adapter.tryClaim(g)).isPresent();
        long nullGroup = group();
        redis.opsForValue()
                .set(
                        "settlement:netting-estimate:v2:{" + nullGroup + "}:data",
                        "null",
                        Duration.ofSeconds(1));
        assertThat(adapter.find(nullGroup)).isEmpty();
        assertThat(adapter.tryClaim(nullGroup)).isPresent();
    }
}
