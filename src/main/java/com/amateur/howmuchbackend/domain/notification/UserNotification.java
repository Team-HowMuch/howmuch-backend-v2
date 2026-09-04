package com.amateur.howmuchbackend.domain.notification;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record UserNotification(UUID id, Long recipientUserId, UUID settlementId,
        NotificationType type, SettlementAllocationType allocationType, String requesterName,
        String title, String message, String deduplicationKey, LocalDateTime readAt, LocalDateTime createdAt) {
    public UserNotification {
        Objects.requireNonNull(id);
        Objects.requireNonNull(recipientUserId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(allocationType);
        Objects.requireNonNull(createdAt);
        requireText(requesterName, 100);
        requireText(title, 100);
        requireText(message, 500);
        requireText(deduplicationKey, 200);
    }

    private static void requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("알림 표시 값이 유효하지 않습니다.");
        }
    }
}
