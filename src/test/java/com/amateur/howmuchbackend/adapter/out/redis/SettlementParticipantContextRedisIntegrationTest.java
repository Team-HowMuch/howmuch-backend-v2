package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.database=15",
        "howmuch.auth.sms.discord.webhook-url=http://localhost"
})
@ActiveProfiles("local")
@DisplayName("정산 링크 참여 문맥 Redis 원자 발급")
class SettlementParticipantContextRedisIntegrationTest {
    private static final String LINK_TOKEN_HASH = "b".repeat(64);

    @Autowired
    private IssueSettlementParticipantContextPort contextPort;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("동일 링크와 참여자의 동시 요청은 하나의 context token을 재사용한다")
    void reusesOneContextForConcurrentRequests() throws Exception {
        UUID settlementId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 12, 0);
        String indexKey = SettlementParticipantContextRedisAdapter.INDEX_KEY_PREFIX
                + LINK_TOKEN_HASH + ":" + participantId;
        List<SettlementParticipantContext> contexts = new ArrayList<>();
        Set<String> contextKeysBefore = contextKeys();
        Set<String> createdContextKeys = new HashSet<>();

        try {
            contexts.addAll(issueConcurrently(settlementId, participantId, now));

            List<UUID> contextIds = contexts.stream()
                    .map(SettlementParticipantContext::id)
                    .distinct()
                    .toList();
            assertThat(contextIds).hasSize(1);
            String contextKey = SettlementParticipantContextRedisAdapter.KEY_PREFIX
                    + contextIds.getFirst();
            createdContextKeys.addAll(contextKeys());
            createdContextKeys.removeAll(contextKeysBefore);
            assertThat(createdContextKeys).containsExactly(contextKey);
            assertThat(redis.hasKey(indexKey)).isTrue();
            assertThat(redis.hasKey(contextKey)).isTrue();
            assertThat(redis.getExpire(indexKey, TimeUnit.MILLISECONDS)).isPositive();
            assertThat(redis.getExpire(contextKey, TimeUnit.MILLISECONDS)).isPositive();
        } finally {
            redis.delete(indexKey);
            createdContextKeys.addAll(contextKeys());
            createdContextKeys.removeAll(contextKeysBefore);
            redis.delete(createdContextKeys);
        }
    }

    private Set<String> contextKeys() {
        return redis.keys(SettlementParticipantContextRedisAdapter.KEY_PREFIX + "*");
    }

    private List<SettlementParticipantContext> issueConcurrently(
            UUID settlementId,
            UUID participantId,
            LocalDateTime now
    ) throws Exception {
        int requestCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<SettlementParticipantContext>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return contextPort.issue(
                            LINK_TOKEN_HASH,
                            settlementId,
                            participantId,
                            now
                    );
                }));
            }
            start.countDown();

            List<SettlementParticipantContext> contexts = new ArrayList<>();
            for (Future<SettlementParticipantContext> future : futures) {
                contexts.add(future.get(10, TimeUnit.SECONDS));
            }
            return contexts;
        } finally {
            executor.shutdownNow();
        }
    }
}
