package com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementLinkJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementReceivingAccountSnapshotJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

public final class SettlementRequestMapper {
    private SettlementRequestMapper() {
    }

    public static SettlementReceivingAccountSnapshotJpaEntity toEntity(
            SettlementReceivingAccountSnapshot snapshot,
            String accountNumberCiphertext
    ) {
        return new SettlementReceivingAccountSnapshotJpaEntity(
                snapshot.settlementId(),
                snapshot.sourceAccountId(),
                snapshot.organization(),
                accountNumberCiphertext,
                snapshot.accountHolder(),
                snapshot.createdAt()
        );
    }

    public static SettlementReceivingAccountSnapshot toDomain(
            SettlementReceivingAccountSnapshotJpaEntity entity,
            String accountNumber
    ) {
        return new SettlementReceivingAccountSnapshot(
                entity.getSettlementId(),
                entity.getSourceAccountId(),
                entity.getOrganization(),
                accountNumber,
                entity.getAccountHolder(),
                entity.getCreatedAt()
        );
    }

    public static SettlementShareJpaEntity toEntity(SettlementShare share) {
        return new SettlementShareJpaEntity(
                share.id(),
                share.settlementId(),
                share.payerParticipantId(),
                share.amount(),
                share.createdAt()
        );
    }

    public static SettlementShare toDomain(SettlementShareJpaEntity entity) {
        return new SettlementShare(
                entity.getId(),
                entity.getSettlementId(),
                entity.getPayerParticipantId(),
                entity.getAmount(),
                entity.getCreatedAt()
        );
    }

    public static SettlementLinkJpaEntity toEntity(SettlementLink link) {
        return new SettlementLinkJpaEntity(
                link.id(),
                link.settlementId(),
                link.tokenHash(),
                link.status(),
                link.expiresAt()
        );
    }

    public static SettlementLink toDomain(SettlementLinkJpaEntity entity) {
        return new SettlementLink(
                entity.getId(),
                entity.getSettlementId(),
                entity.getTokenHash(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
