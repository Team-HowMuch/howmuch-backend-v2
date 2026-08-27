package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemSelectionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementItemSelectionJpaRepository
        extends JpaRepository<SettlementItemSelectionJpaEntity, UUID> {
    List<SettlementItemSelectionJpaEntity> findAllBySettlementId(
            UUID settlementId
    );

    Optional<SettlementItemSelectionJpaEntity>
    findBySettlementIdAndItemIdAndParticipantId(
            UUID settlementId,
            UUID itemId,
            UUID participantId
    );
}
