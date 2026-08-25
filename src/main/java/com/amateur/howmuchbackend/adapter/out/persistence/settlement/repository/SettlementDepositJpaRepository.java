package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDepositJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementDepositJpaRepository
        extends JpaRepository<SettlementDepositJpaEntity, UUID> {
    List<SettlementDepositJpaEntity> findAllBySettlementIdOrderByMatchedAtAscIdAsc(
            UUID settlementId
    );

    List<SettlementDepositJpaEntity> findAllByAccountTransactionIdIn(
            Collection<Long> transactionIds
    );
}
