package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(name = "settlement_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItemJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(nullable = false, updatable = false, length = SettlementItem.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    public SettlementItemJpaEntity(SettlementItem item) {
        this.id = item.id();
        this.settlementId = item.settlementId();
        this.expenseId = item.expenseId();
        this.name = item.name();
        this.quantity = item.quantity();
        this.amount = item.amount();
        this.displayOrder = item.displayOrder();
    }
}
