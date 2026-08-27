package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(
        name = "settlements",
        indexes = @Index(
                name = "idx_settlements_creator_created_at",
                columnList = "creator_id, created_at"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(nullable = false, length = Settlement.MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_type", length = 30)
    private SettlementAllocationType allocationType;

    @Column(name = "receiving_account_id")
    private Long receivingAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SettlementStatus status;

    public SettlementJpaEntity(Settlement settlement) {
        this.id = settlement.id();
        this.creatorId = settlement.creatorId();
        updateFrom(settlement);
    }

    public void updateFrom(Settlement settlement) {
        this.title = settlement.title();
        this.totalAmount = settlement.totalAmount();
        this.allocationType = settlement.allocationType();
        this.receivingAccountId = settlement.receivingAccountId();
        this.status = settlement.status();
    }
}
