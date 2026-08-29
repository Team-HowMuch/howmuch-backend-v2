package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.database=15",
        "howmuch.auth.sms.discord.webhook-url=http://localhost"
})
@ActiveProfiles("local")
@DisplayName("정산 독촉 Redis 30분 제한")
class SettlementReminderCooldownRedisIntegrationTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final String KEY = SettlementReminderCooldownRedisAdapter.KEY_PREFIX
            + SETTLEMENT_ID + ":" + PARTICIPANT_ID;

    @Autowired private SettlementReminderCooldownPort cooldownPort;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clearCooldown() {
        redis.delete(KEY);
    }

    @Test
    @DisplayName("첫 요청만 예약하고 일치하는 실패 요청만 예약을 해제한다")
    void reservesAndConditionallyReleasesCooldown() {
        Duration cooldown = Duration.ofMinutes(30);

        assertThat(cooldownPort.reserve(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                "first-reservation",
                cooldown
        )).isTrue();
        assertThat(cooldownPort.reserve(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                "second-reservation",
                cooldown
        )).isFalse();

        cooldownPort.release(SETTLEMENT_ID, PARTICIPANT_ID, "second-reservation");
        assertThat(redis.hasKey(KEY)).isTrue();
        assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(cooldown.toMillis());

        cooldownPort.release(SETTLEMENT_ID, PARTICIPANT_ID, "first-reservation");
        assertThat(redis.hasKey(KEY)).isFalse();
    }
}
