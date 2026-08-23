package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.TrackSettlementContactMatchUsagePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("연락처 매칭 Redis 사용량 Adapter")
class SettlementContactMatchUsageRedisIntegrationTest {
    private static final Long TEST_USER_ID = 9_000_000_085L;
    private static final String REQUEST_KEY =
            SettlementContactMatchUsageRedisAdapter.REQUEST_KEY_PREFIX + TEST_USER_ID;
    private static final String CONTACT_KEY =
            SettlementContactMatchUsageRedisAdapter.CONTACT_KEY_PREFIX + TEST_USER_ID;

    @Autowired
    private TrackSettlementContactMatchUsagePort usagePort;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clearUsage() {
        redis.delete(List.of(REQUEST_KEY, CONTACT_KEY));
    }

    @Test
    @DisplayName("사용자별 요청 수와 연락처 수를 원자 누적하고 각각 TTL을 설정한다")
    void recordsUsageWithIndependentWindows() {
        Duration requestWindow = Duration.ofMinutes(1);
        Duration contactWindow = Duration.ofHours(24);

        var first = usagePort.recordUsage(TEST_USER_ID, 3, requestWindow, contactWindow);
        var second = usagePort.recordUsage(TEST_USER_ID, 4, requestWindow, contactWindow);

        assertThat(first.requestCount()).isEqualTo(1);
        assertThat(first.contactCount()).isEqualTo(3);
        assertThat(second.requestCount()).isEqualTo(2);
        assertThat(second.contactCount()).isEqualTo(7);
        assertThat(redis.getExpire(REQUEST_KEY, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(requestWindow.toMillis());
        assertThat(redis.getExpire(CONTACT_KEY, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(contactWindow.toMillis());
    }
}
