package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "settlement_item_submissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_item_submissions_settlement_participant",
                columnNames = {"settlement_id", "participant_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItemSubmissionJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    public SettlementItemSubmissionJpaEntity(SettlementItemSubmission submission) {
        this.id = submission.id();
        this.settlementId = submission.settlementId();
        this.participantId = submission.participantId();
        this.submittedAt = submission.submittedAt();
    }
}
