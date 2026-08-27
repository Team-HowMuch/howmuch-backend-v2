package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
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
        name = "settlement_participants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_settlement_participants_settlement_linked_user",
                        columnNames = {"settlement_id", "linked_user_id"}
                ),
                @UniqueConstraint(
                        name = "uk_settlement_participants_settlement_phone_hash",
                        columnNames = {"settlement_id", "phone_hash"}
                ),
                @UniqueConstraint(
                        name = "uk_settlement_participants_settlement_display_order",
                        columnNames = {"settlement_id", "display_order"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementParticipantJpaEntity extends BaseTimeJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private SettlementParticipantSource source;

    @Column(name = "linked_user_id", updatable = false)
    private Long linkedUserId;

    @Column(name = "phone_hash", updatable = false, length = SettlementParticipant.PHONE_HASH_LENGTH)
    private String phoneHash;

    @Column(name = "source_group_id", updatable = false)
    private Long sourceGroupId;

    @Column(
            name = "display_name",
            nullable = false,
            updatable = false,
            length = SettlementParticipant.MAX_DISPLAY_NAME_LENGTH
    )
    private String displayName;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    public SettlementParticipantJpaEntity(SettlementParticipant participant) {
        this.id = participant.id();
        this.settlementId = participant.settlementId();
        this.source = participant.source();
        this.linkedUserId = participant.linkedUserId();
        this.phoneHash = participant.phoneHash();
        this.sourceGroupId = participant.sourceGroupId();
        this.displayName = participant.displayName();
        this.displayOrder = participant.displayOrder();
    }
}
