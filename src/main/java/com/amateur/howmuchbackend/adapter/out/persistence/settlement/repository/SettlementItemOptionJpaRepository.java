package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemOptionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementItemOptionJpaRepository
        extends JpaRepository<SettlementItemOptionJpaEntity, UUID> {
    List<SettlementItemOptionJpaEntity> findBySettlementIdInOrderByItemIdAscDisplayOrderAsc(
            Collection<UUID> settlementIds
    );

    List<SettlementItemOptionJpaEntity> findBySettlementIdAndItemIdInOrderByItemIdAscDisplayOrderAsc(
            UUID settlementId, Collection<UUID> itemIds
    );
}
