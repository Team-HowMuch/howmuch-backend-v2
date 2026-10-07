package com.amateur.howmuchbackend.domain.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NotificationTargetTest {
    @ParameterizedTest
    @CsvSource({"GROUP_DETAIL,0", "GROUP_DETAIL,-1", "GROUP_DETAIL,001", "GROUP_DETAIL,9223372036854775808",
            "MONTHLY_RECAP,2026-13", "MONTHLY_RECAP,0000-01", "MONTHLY_RECAP,2026-1",
            "GROUP_INVITATION,not-a-uuid", "SETTLEMENT_DETAIL,1-1-1-1-1"})
    void targetRejectsInvalidIdentifiers(NotificationTargetType type, String id) {
        assertThatThrownBy(() -> new NotificationTarget(type, id)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void genericNotificationHasNoSettlementAllocation_andRejectsMismatchedTarget() {
        var target = new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09");
        var notification = new UserNotification(UUID.randomUUID(), 1L, null, NotificationType.MONTHLY_RECAP,
                null, "HowMuch", "월간 리캡", "내역", "recap-2026-09", null, LocalDateTime.of(2026, 10, 1, 0, 0), target);
        assertThat(notification.allocationType()).isNull();
        assertThat(notification.target()).isEqualTo(target);
        assertThatThrownBy(() -> new UserNotification(UUID.randomUUID(), 1L, null, NotificationType.GROUP_INVITATION,
                null, "초대자", "초대", "내역", "invite-key", null, notification.createdAt(), target))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
