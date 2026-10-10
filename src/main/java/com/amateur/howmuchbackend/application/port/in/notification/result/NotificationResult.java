package com.amateur.howmuchbackend.application.port.in.notification.result;

import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResult(UUID id, NotificationType type, SettlementAllocationType allocationType,
        String title, String message, LocalDateTime createdAt, LocalDateTime readAt, UUID settlementId,
        Action action) {
    public record Action(String type, String label, boolean enabled, String targetId) {
        public Action(String type, String label, boolean enabled) { this(type, label, enabled, null); }
    }
}
