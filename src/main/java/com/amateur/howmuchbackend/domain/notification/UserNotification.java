package com.amateur.howmuchbackend.domain.notification;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record UserNotification(UUID id, Long recipientUserId, UUID settlementId,
        NotificationType type, SettlementAllocationType allocationType, String requesterName,
        String title, String message, String deduplicationKey, LocalDateTime readAt, LocalDateTime createdAt,
        NotificationTarget target) {
    public UserNotification(UUID id, Long recipientUserId, UUID settlementId,
            NotificationType type, SettlementAllocationType allocationType, String requesterName,
            String title, String message, String deduplicationKey, LocalDateTime readAt, LocalDateTime createdAt) {
        this(id, recipientUserId, settlementId, type, allocationType, requesterName, title, message,
                deduplicationKey, readAt, createdAt, settlementId == null ? null : new NotificationTarget(
                        type == NotificationType.SETTLEMENT_REQUEST ? NotificationTargetType.SETTLEMENT_PARTICIPATION
                                : NotificationTargetType.SETTLEMENT_DETAIL, settlementId.toString()));
    }
    public UserNotification {
        Objects.requireNonNull(id);
        Objects.requireNonNull(recipientUserId);
        Objects.requireNonNull(type);
        if (recipientUserId < 1) throw new IllegalArgumentException("알림 수신자는 양수여야 합니다.");
        if (target != null && target.type().isSettlement()) {
            Objects.requireNonNull(allocationType, "정산 알림에는 배분 방식이 필요합니다.");
            if (settlementId != null && !settlementId.toString().equals(target.id()))
                throw new IllegalArgumentException("정산 알림 이동 식별자가 일치하지 않습니다.");
        } else if (target != null && settlementId != null) {
            throw new IllegalArgumentException("정산 외 알림에는 정산 식별자를 넣을 수 없습니다.");
        }
        if (target != null) {
            boolean compatible = switch (type) {
                case SETTLEMENT_REQUEST, SETTLEMENT_REMINDER, SETTLEMENT_PAYMENT_COMPLETED, SETTLEMENT_COMPLETED
                        -> target.type().isSettlement();
                case SETTLEMENT_DEPOSIT_CONFIRMED -> target.type() == NotificationTargetType.SETTLEMENT_DETAIL;
                case GROUP_INVITATION -> target.type() == NotificationTargetType.GROUP_INVITATION;
                case GROUP_MEMBER_LEFT -> target.type() == NotificationTargetType.GROUP_DETAIL;
                case GROUP_NETTING_CONFIRMED,
                                        GROUP_NETTING_COMPLETED,
                                        NETTED_TRANSFER_REMINDER ->
                                target.type() == NotificationTargetType.GROUP_SETTLEMENT_NETTING;
                        case FINANCE_AUTH_REQUIRED -> target.type() == NotificationTargetType.FINANCE_AUTHENTICATION;
                        case MONTHLY_RECAP -> target.type() == NotificationTargetType.MONTHLY_RECAP;
            };
            if (!compatible || !target.type().isSettlement() && allocationType != null)
                throw new IllegalArgumentException("알림 종류와 이동 대상이 일치하지 않습니다.");
        }
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
