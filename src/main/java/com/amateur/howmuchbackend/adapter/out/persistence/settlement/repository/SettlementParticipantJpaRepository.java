package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Query("""
            select distinct settlement.status
              from SettlementJpaEntity settlement,
                   SettlementParticipantJpaEntity participant
             where settlement.id = participant.settlementId
               and settlement.deletedAt is null
               and participant.source = :source
               and participant.sourceGroupId = :groupId
               and participant.linkedUserId = :userId
            """)
    Set<SettlementStatus> findDistinctSettlementStatusesByGroupMember(
            @Param("source") SettlementParticipantSource source,
            @Param("groupId") Long groupId,
            @Param("userId") Long userId
    );
}
