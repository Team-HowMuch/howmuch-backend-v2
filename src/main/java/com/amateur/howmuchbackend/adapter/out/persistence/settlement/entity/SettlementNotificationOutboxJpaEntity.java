package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(
        name = "settlement_notification_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_notification_outbox_settlement_recipient",
                columnNames = {"settlement_id", "recipient_user_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementNotificationOutboxJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementNotificationOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_code", length = 40)
    private SettlementNotificationFailureCode lastFailureCode;

    public SettlementNotificationOutboxJpaEntity(SettlementNotificationOutbox event) {
        id = event.id();
        settlementId = event.settlementId();
        recipientUserId = event.recipientUserId();
        apply(event);
    }

    public void apply(SettlementNotificationOutbox event) {
        if (!id.equals(event.id())
                || !settlementId.equals(event.settlementId())
                || !recipientUserId.equals(event.recipientUserId())) {
            throw new IllegalArgumentException("알림 Outbox 식별자는 변경할 수 없습니다.");
        }
        status = event.status();
        attemptCount = event.attemptCount();
        nextAttemptAt = event.nextAttemptAt();
        processingStartedAt = event.processingStartedAt();
        completedAt = event.completedAt();
        lastFailureCode = event.lastFailureCode();
    }
}
