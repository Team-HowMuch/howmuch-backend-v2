package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
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

import java.util.UUID;

@Entity
@Getter
@Table(
        name = "settlement_item_selections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_item_selections_item_participant",
                columnNames = {"settlement_id", "item_id", "participant_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItemSelectionJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemAllocationFraction fraction;

    public SettlementItemSelectionJpaEntity(SettlementItemSelection selection) {
        this.id = selection.id();
        this.settlementId = selection.settlementId();
        this.itemId = selection.itemId();
        this.participantId = selection.participantId();
        this.fraction = selection.fraction();
    }

    public void updateFrom(SettlementItemSelection selection) {
        this.fraction = selection.fraction();
    }
}
