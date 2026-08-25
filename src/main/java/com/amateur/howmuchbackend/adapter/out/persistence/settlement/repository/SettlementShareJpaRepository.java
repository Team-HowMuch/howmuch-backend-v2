package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementShareJpaRepository extends JpaRepository<SettlementShareJpaEntity, UUID> {
    boolean existsBySettlementId(UUID settlementId);

    Optional<SettlementShareJpaEntity> findBySettlementIdAndPayerParticipantId(
            UUID settlementId,
            UUID payerParticipantId
    );
}
