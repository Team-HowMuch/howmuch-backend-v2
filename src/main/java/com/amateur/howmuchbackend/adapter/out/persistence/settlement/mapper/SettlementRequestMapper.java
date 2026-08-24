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

    public static SettlementShareJpaEntity toEntity(SettlementShare share) {
        return new SettlementShareJpaEntity(
                share.id(),
                share.settlementId(),
                share.payerParticipantId(),
                share.amount(),
                share.createdAt()
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
}
