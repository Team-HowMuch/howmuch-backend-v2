package com.amateur.howmuchbackend.application.settlement.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정산 참여 알림 재시도 Policy")
class SettlementNotificationRetryPolicyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 12, 0);

    private final SettlementNotificationRetryPolicy policy =
            new SettlementNotificationRetryPolicy();

    @Test
    @DisplayName("Application이 최대 시도 횟수와 지수 backoff를 소유한다")
    void notificationRetryPolicy_retryRules_ownedByApplication() {
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(5));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(10));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(20));
        assertThat(policy.nextAttemptAt(4, NOW)).isEqualTo(NOW.plusSeconds(40));
        assertThat(policy.nextAttemptAt(5, NOW)).isEqualTo(NOW.plusMinutes(1));
        assertThat(policy.staleBefore(NOW)).isEqualTo(NOW.minusMinutes(2));
    }
}
