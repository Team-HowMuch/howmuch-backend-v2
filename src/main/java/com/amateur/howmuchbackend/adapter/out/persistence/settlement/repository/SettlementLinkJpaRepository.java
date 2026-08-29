package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementLinkJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementLinkJpaRepository extends JpaRepository<SettlementLinkJpaEntity, UUID> {
    Optional<SettlementLinkJpaEntity> findByTokenHash(String tokenHash);

    Optional<SettlementLinkJpaEntity> findBySettlementId(UUID settlementId);
}
