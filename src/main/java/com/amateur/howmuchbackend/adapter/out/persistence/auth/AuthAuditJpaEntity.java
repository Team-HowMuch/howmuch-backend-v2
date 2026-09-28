package com.amateur.howmuchbackend.adapter.out.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_audit_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthAuditJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "target_user_id")
    private Long targetUserId;

    public AuthAuditJpaEntity(Long userId, LocalDateTime occurredAt, String clientIp, String userAgent) {
        this.userId = userId;
        this.eventType = "LOGOUT";
        this.occurredAt = occurredAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    public AuthAuditJpaEntity(Long adminUserId, Long targetUserId, LocalDateTime occurredAt) {
        this.userId = adminUserId;
        this.eventType = "ADMIN_USER_DELETION";
        this.targetUserId = targetUserId;
        this.occurredAt = occurredAt;
    }
}
