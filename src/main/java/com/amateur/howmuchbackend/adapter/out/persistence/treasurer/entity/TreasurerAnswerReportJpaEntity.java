package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity;

import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReportReason;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReportStatus;
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

/**
 * BaseTimeJpaEntity를 상속하지 않는다. 신고의 변경은 resolved_at이 기록한다.
 */
@Entity
@Table(name = "treasurer_answer_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TreasurerAnswerReportJpaEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "answer_id", nullable = false)
    private UUID answerId;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private TreasurerAnswerReportReason reason;

    @Column(name = "detail", length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TreasurerAnswerReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public TreasurerAnswerReportJpaEntity(
            UUID id,
            UUID answerId,
            Long reporterUserId,
            TreasurerAnswerReportReason reason,
            String detail,
            TreasurerAnswerReportStatus status,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt
    ) {
        this.id = id;
        this.answerId = answerId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.detail = detail;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }
}
