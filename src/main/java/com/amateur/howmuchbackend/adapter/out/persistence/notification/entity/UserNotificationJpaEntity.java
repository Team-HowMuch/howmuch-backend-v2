package com.amateur.howmuchbackend.adapter.out.persistence.notification.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.domain.notification.NotificationTargetType;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotificationJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;
    @Column(name = "settlement_id", nullable = true)
    private UUID settlementId;
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 30)
    private NotificationType notificationType;
    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", length = 30)
    private SettlementAllocationType allocationType;
    @Column(name = "requester_name", nullable = false, length = 100)
    private String requesterName;
    @Column(name = "title", nullable = false, length = 100)
    private String title;
    @Column(name = "message", nullable = false, length = 500)
    private String message;
    @Column(name = "deduplication_key", nullable = false, length = 200)
    private String deduplicationKey;
    @Column(name = "read_at", nullable = true)
    private LocalDateTime readAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 40)
    private NotificationTargetType targetType;
    @Column(name = "target_id", length = 64)
    private String targetId;
}
