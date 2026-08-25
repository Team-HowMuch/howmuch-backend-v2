package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemSubmissionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementItemSubmissionJpaRepository
        extends JpaRepository<SettlementItemSubmissionJpaEntity, UUID> {
    List<SettlementItemSubmissionJpaEntity> findAllBySettlementId(
            UUID settlementId
    );

    Optional<SettlementItemSubmissionJpaEntity>
    findBySettlementIdAndParticipantId(
            UUID settlementId,
            UUID participantId
    );

    void deleteAllBySettlementId(UUID settlementId);
}
