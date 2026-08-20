package com.amateur.howmuchbackend.adapter.out.persistence.account.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ExternalConnectionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.user.UserJpaEntity;
import com.amateur.howmuchbackend.domain.account.AccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@Table(
        name = "accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_accounts_connection_candidate",
                columnNames = {"external_connection_id", "candidate_key"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_connection_id", nullable = false)
    private ExternalConnectionJpaEntity externalConnection;

    @Column(nullable = false, length = 10)
    private String organization;

    @Column(name = "candidate_key", nullable = false, length = 64)
    private String candidateKey;

    @Column(name = "external_account_key_ciphertext", nullable = false, length = 1024)
    private String externalAccountKeyCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountType type;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "masked_number", nullable = false, length = 64)
    private String maskedNumber;

    @Column(precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(length = 10)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    public AccountJpaEntity(
            UserJpaEntity user,
            ExternalConnectionJpaEntity externalConnection,
            String organization,
            String candidateKey
    ) {
        this.user = user;
        this.externalConnection = externalConnection;
        this.organization = organization;
        this.candidateKey = candidateKey;
    }

    public void update(
            String externalAccountKeyCiphertext,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency
    ) {
        this.externalAccountKeyCiphertext = externalAccountKeyCiphertext;
        this.type = type;
        this.name = name;
        this.maskedNumber = maskedNumber;
        this.balance = balance;
        this.currency = currency;
        this.active = true;
    }
}
