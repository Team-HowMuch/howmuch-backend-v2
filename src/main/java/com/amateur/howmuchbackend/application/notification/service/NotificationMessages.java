package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.domain.notification.*;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import java.time.LocalDateTime;
import java.util.UUID;

public final class NotificationMessages {
    private NotificationMessages() {}
    public static UserNotification request(UUID settlementId, Long recipient, SettlementAllocationType allocation,
            String requester, LocalDateTime now) {
        String subject = allocation == SettlementAllocationType.ITEM_ALLOCATION ? "영수증 정산" : "정산";
        return new UserNotification(UUID.randomUUID(), recipient, settlementId, NotificationType.SETTLEMENT_REQUEST,
                allocation, requester, "정산 요청", requester + "님이 " + subject + "을 요청했어요",
                "SETTLEMENT_REQUEST:" + settlementId, null, now);
    }
    public static UserNotification reminder(UUID settlementId, Long recipient, SettlementAllocationType allocation,
            String requester, String key, String message, LocalDateTime now) {
        String title = key.startsWith("AUTO_REMINDER:") ? "정산 리마인드" : "정산 독촉";
        return new UserNotification(UUID.randomUUID(), recipient, settlementId, NotificationType.SETTLEMENT_REMINDER,
                allocation == null ? SettlementAllocationType.DUTCH_PAY : allocation, requester, title, message, key, null, now);
    }
}
