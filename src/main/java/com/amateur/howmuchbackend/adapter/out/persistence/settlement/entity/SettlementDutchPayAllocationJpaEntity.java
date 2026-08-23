package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
        name = "settlement_dutch_pay_allocations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_dutch_pay_allocations_settlement_participant",
                columnNames = {"settlement_id", "participant_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDutchPayAllocationJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Column(nullable = false)
    private long amount;

    public SettlementDutchPayAllocationJpaEntity(SettlementDutchPayAllocation allocation) {
        this.id = allocation.id();
        this.settlementId = allocation.settlementId();
        this.participantId = allocation.participantId();
        this.amount = allocation.amount();
    }

    public void updateFrom(SettlementDutchPayAllocation allocation) {
        this.amount = allocation.amount();
    }
}
