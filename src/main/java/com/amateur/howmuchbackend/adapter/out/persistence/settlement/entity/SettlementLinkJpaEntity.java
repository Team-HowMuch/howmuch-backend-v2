package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(
        name = "settlement_links",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_settlement_links_settlement",
                        columnNames = "settlement_id"
                ),
                @UniqueConstraint(
                        name = "uk_settlement_links_token_hash",
                        columnNames = "token_hash"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementLinkJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "token_hash", nullable = false, length = SettlementLink.TOKEN_HASH_LENGTH)
    private String tokenHash;

    @Column(name = "token_ciphertext", length = 1024)
    private String tokenCiphertext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementLinkStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public SettlementLinkJpaEntity(
            UUID id,
            UUID settlementId,
            String tokenHash,
            String tokenCiphertext,
            SettlementLinkStatus status,
            LocalDateTime expiresAt
    ) {
        this.id = id;
        this.settlementId = settlementId;
        this.tokenHash = tokenHash;
        this.tokenCiphertext = tokenCiphertext;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    public SettlementLinkJpaEntity(
            UUID id,
            UUID settlementId,
            String tokenHash,
            SettlementLinkStatus status,
            LocalDateTime expiresAt
    ) {
        this(id, settlementId, tokenHash, null, status, expiresAt);
    }

    public void replaceToken(
            String tokenHash,
            String tokenCiphertext,
            LocalDateTime expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.tokenCiphertext = tokenCiphertext;
        this.status = SettlementLinkStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }
}
