package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

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
        name = "settlement_shares",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_shares_settlement_payer",
                columnNames = {"settlement_id", "payer_participant_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementShareJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "payer_participant_id", nullable = false, updatable = false)
    private UUID payerParticipantId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SettlementShareJpaEntity(
            UUID id,
            UUID settlementId,
            UUID payerParticipantId,
            long amount,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.settlementId = settlementId;
        this.payerParticipantId = payerParticipantId;
        this.amount = amount;
        this.createdAt = createdAt;
    }
}
