package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(name = "settlement_expenses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementExpenseJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private SettlementExpenseSource source;

    @Column(
            name = "source_reference",
            updatable = false,
            length = SettlementExpense.MAX_SOURCE_REFERENCE_LENGTH
    )
    private String sourceReference;

    @Column(name = "source_account_id", updatable = false)
    private Long sourceAccountId;

    @Column(nullable = false, length = SettlementExpense.MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(nullable = false)
    private long amount;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    public SettlementExpenseJpaEntity(SettlementExpense expense) {
        this.id = expense.id();
        this.settlementId = expense.settlementId();
        this.source = expense.source();
        this.sourceReference = expense.sourceReference();
        this.sourceAccountId = expense.sourceAccountId();
        this.displayOrder = expense.displayOrder();
        updateFrom(expense);
    }

    public void updateFrom(SettlementExpense expense) {
        this.description = expense.description();
        this.amount = expense.amount();
    }
}
