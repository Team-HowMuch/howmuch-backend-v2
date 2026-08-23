package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDutchPayAllocationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementDutchPayAllocationJpaRepository
        extends JpaRepository<SettlementDutchPayAllocationJpaEntity, UUID> {
    List<SettlementDutchPayAllocationJpaEntity> findAllBySettlementId(UUID settlementId);

    Optional<SettlementDutchPayAllocationJpaEntity> findBySettlementIdAndParticipantId(
            UUID settlementId,
            UUID participantId
    );

    void deleteAllBySettlementId(UUID settlementId);
}
