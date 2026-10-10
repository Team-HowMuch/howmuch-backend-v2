package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.domain.notification.NotificationTarget;
import com.amateur.howmuchbackend.domain.notification.NotificationTargetType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class GenericNotificationPayloadTest {
    @Test void genericPayloadContainsTypedTargetWithoutNullSettlementFields() {
        UUID id = UUID.randomUUID();
        var message = new SettlementPushSenderPort.NotificationMessage(id, null, "MONTHLY_RECAP", null,
                "월간 리캡", "지난달 정산 내역", new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09"));
        var data = FirebaseSettlementPushSenderAdapter.notificationData(message);
        assertThat(data).containsEntry("notificationId", id.toString()).containsEntry("type", "MONTHLY_RECAP")
                .containsEntry("targetType", "MONTHLY_RECAP").containsEntry("targetId", "2026-09")
                .doesNotContainKeys("settlementId", "allocationType", "shareLink");
    }
}
