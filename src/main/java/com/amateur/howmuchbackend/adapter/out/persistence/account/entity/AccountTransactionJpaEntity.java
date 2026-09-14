package com.amateur.howmuchbackend.adapter.out.persistence.account.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "account_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountTransactionJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "external_transaction_key", nullable = false, length = 64)
    private String externalTransactionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private AccountTransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "transacted_at", nullable = false)
    private LocalDateTime transactedAt;

    @Column(length = 500)
    private String description;

    @Column(name = "description_1", length = 500)
    private String description1;

    @Column(name = "description_2", length = 500)
    private String description2;

    @Column(name = "description_3", length = 500)
    private String description3;

    @Column(name = "description_4", length = 500)
    private String description4;

    @Column(name = "after_balance", precision = 19, scale = 4)
    private BigDecimal afterBalance;
}
