package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(name = "settlement_deposits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDepositJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private Long sourceAccountId;

    @Column(name = "account_transaction_id", nullable = false, updatable = false)
    private Long accountTransactionId;

    @Column(name = "matched_at", nullable = false, updatable = false)
    private LocalDateTime matchedAt;

    public SettlementDepositJpaEntity(
            UUID id,
            UUID settlementId,
            UUID shareId,
            Long sourceAccountId,
            Long accountTransactionId,
            LocalDateTime matchedAt
    ) {
        this.id = id;
        this.settlementId = settlementId;
        this.shareId = shareId;
        this.sourceAccountId = sourceAccountId;
        this.accountTransactionId = accountTransactionId;
        this.matchedAt = matchedAt;
    }
}
