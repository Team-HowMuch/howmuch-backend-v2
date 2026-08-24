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
@Table(name = "settlement_receiving_account_snapshots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementReceivingAccountSnapshotJpaEntity {
    @Id
    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private Long sourceAccountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String organization;

    @Column(name = "account_number_ciphertext", nullable = false, updatable = false, length = 1024)
    private String accountNumberCiphertext;

    @Column(name = "account_holder", nullable = false, updatable = false, length = 100)
    private String accountHolder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SettlementReceivingAccountSnapshotJpaEntity(
            UUID settlementId,
            Long sourceAccountId,
            String organization,
            String accountNumberCiphertext,
            String accountHolder,
            LocalDateTime createdAt
    ) {
        this.settlementId = settlementId;
        this.sourceAccountId = sourceAccountId;
        this.organization = organization;
        this.accountNumberCiphertext = accountNumberCiphertext;
        this.accountHolder = accountHolder;
        this.createdAt = createdAt;
    }
}
