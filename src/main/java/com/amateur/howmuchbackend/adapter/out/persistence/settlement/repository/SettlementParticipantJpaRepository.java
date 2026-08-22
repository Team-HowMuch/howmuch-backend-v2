package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementParticipantJpaRepository
        extends JpaRepository<SettlementParticipantJpaEntity, UUID> {
    List<SettlementParticipantJpaEntity> findAllBySettlementIdOrderByDisplayOrderAsc(
            UUID settlementId
    );

    Optional<SettlementParticipantJpaEntity> findBySettlementIdAndId(
            UUID settlementId,
            UUID participantId
    );

    boolean existsBySettlementIdAndLinkedUserId(UUID settlementId, Long linkedUserId);

    boolean existsBySettlementIdAndPhoneHash(UUID settlementId, String phoneHash);

    Optional<SettlementParticipantJpaEntity> findFirstBySettlementIdOrderByDisplayOrderDesc(
            UUID settlementId
    );
}
