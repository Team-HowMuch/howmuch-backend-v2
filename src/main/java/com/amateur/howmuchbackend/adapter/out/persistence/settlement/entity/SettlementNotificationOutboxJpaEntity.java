package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import java.util.Objects;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(name = "settlement_notification_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementNotificationOutboxJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", updatable = false)
    private UUID notificationId;

    @Column(name = "settlement_id", updatable = false)
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

    @Column(name = "delivery_generation", nullable = false)
    private long deliveryGeneration;
    @Column(name = "delivery_message_id", nullable = false)
    private UUID deliveryMessageId = UUID.randomUUID();
    @Column(name = "publication_status", nullable = false, length = 20)
    private String publicationStatus = "PENDING";
    @Column(name = "publication_attempt_count", nullable = false)
    private int publicationAttemptCount;
    @Column(name = "next_publication_at")
    private LocalDateTime nextPublicationAt;
    @Column(name = "publication_lease_token")
    private UUID publicationLeaseToken;
    @Column(name = "publication_lease_until")
    private LocalDateTime publicationLeaseUntil;
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public SettlementNotificationOutboxJpaEntity(SettlementNotificationOutbox event) {
        id = event.id();
        notificationId = event.notificationId();
        settlementId = event.settlementId();
        recipientUserId = event.recipientUserId();
        apply(event);
    }

    public void apply(SettlementNotificationOutbox event) {
        if (!Objects.equals(notificationId, event.notificationId())
                || !id.equals(event.id())
                || !Objects.equals(settlementId, event.settlementId())
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

    public void resetPublicationForRetry(LocalDateTime nextAt) {
        deliveryGeneration++;
        deliveryMessageId = UUID.randomUUID();
        publicationStatus = "PENDING";
        publicationAttemptCount = 0;
        nextPublicationAt = nextAt;
        publicationLeaseToken = null;
        publicationLeaseUntil = null;
        publishedAt = null;
    }
}
