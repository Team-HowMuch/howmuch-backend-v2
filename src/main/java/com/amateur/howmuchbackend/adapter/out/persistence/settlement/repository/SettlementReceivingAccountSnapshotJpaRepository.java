package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementReceivingAccountSnapshotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementReceivingAccountSnapshotJpaRepository
        extends JpaRepository<SettlementReceivingAccountSnapshotJpaEntity, UUID> {
}
