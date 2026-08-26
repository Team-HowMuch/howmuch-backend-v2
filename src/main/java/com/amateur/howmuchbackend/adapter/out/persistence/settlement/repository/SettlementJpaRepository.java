package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SettlementJpaRepository extends JpaRepository<SettlementJpaEntity, UUID> {
    @Query(value = """
            SELECT
                settlement.id AS settlementId,
                settlement.status AS status,
                settlement.created_at AS requestedAt,
                settlement.total_amount AS totalAmount,
                creator.name AS requesterName,
                COUNT(DISTINCT participant.id) AS participantCount,
                MAX(CASE
                        WHEN participant.linked_user_id = :userId THEN share.amount
                    END) AS amountToPay
              FROM settlements settlement
              JOIN users creator
                ON creator.id = settlement.creator_id
              LEFT JOIN settlement_participants participant
                ON participant.settlement_id = settlement.id
              LEFT JOIN settlement_shares share
                ON share.settlement_id = settlement.id
               AND share.payer_participant_id = participant.id
             WHERE (
                    (:tab = 'RECEIVE' AND settlement.creator_id = :userId)
                    OR (
                        :tab = 'PAY'
                        AND settlement.creator_id <> :userId
                        AND EXISTS (
                            SELECT 1
                              FROM settlement_participants linked_participant
                             WHERE linked_participant.settlement_id = settlement.id
                               AND linked_participant.linked_user_id = :userId
                        )
                    )
                )
               AND (
                    :status = 'ALL'
                    OR (
                        :status = 'IN_PROGRESS'
                        AND settlement.status IN (
                            'DRAFT', 'REQUESTED', 'AWAITING_DEPOSIT', 'SUSPENDED'
                        )
                    )
                    OR settlement.status = :status
                )
             GROUP BY settlement.id, settlement.status, settlement.created_at,
                      settlement.total_amount, creator.name
             ORDER BY settlement.created_at DESC, settlement.id DESC
            """, nativeQuery = true)
    List<SettlementListProjection> findAllForUser(
            @Param("userId") Long userId,
            @Param("tab") String tab,
            @Param("status") String status
    );

    @Query(value = """
            SELECT
                settlement.id AS settlementId,
                settlement.title AS title,
                settlement.status AS status,
                settlement.created_at AS requestedAt,
                creator.name AS requesterName,
                MAX(CASE
                        WHEN participant.linked_user_id = :userId THEN share.amount
                    END) AS amountToPay,
                COALESCE(SUM(CASE
                        WHEN participant.linked_user_id IS NULL
                             OR participant.linked_user_id <> settlement.creator_id
                        THEN share.amount
                    END), 0) AS receivableAmount
              FROM settlements settlement
              JOIN users creator
                ON creator.id = settlement.creator_id
              LEFT JOIN settlement_participants participant
                ON participant.settlement_id = settlement.id
              LEFT JOIN settlement_shares share
                ON share.settlement_id = settlement.id
               AND share.payer_participant_id = participant.id
             WHERE (
                    (:tab = 'RECEIVE' AND settlement.creator_id = :userId)
                    OR (
                        :tab = 'PAY'
                        AND settlement.creator_id <> :userId
                        AND EXISTS (
                            SELECT 1
                              FROM settlement_participants linked_participant
                             WHERE linked_participant.settlement_id = settlement.id
                               AND linked_participant.linked_user_id = :userId
                        )
                    )
                )
             GROUP BY settlement.id, settlement.title, settlement.status, settlement.created_at,
                      creator.name
             ORDER BY settlement.created_at DESC, settlement.id DESC
            """, nativeQuery = true)
    List<TreasurerSettlementProjection> findAllForTreasurer(
            @Param("userId") Long userId,
            @Param("tab") String tab
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settlement from SettlementJpaEntity settlement where settlement.id = :settlementId")
    Optional<SettlementJpaEntity> findByIdForUpdate(@Param("settlementId") UUID settlementId);

    interface SettlementListProjection {
        UUID getSettlementId();

        String getStatus();

        LocalDateTime getRequestedAt();

        long getTotalAmount();

        String getRequesterName();

        long getParticipantCount();

        Long getAmountToPay();
    }

    interface TreasurerSettlementProjection {
        UUID getSettlementId();

        String getTitle();

        String getStatus();

        LocalDateTime getRequestedAt();

        String getRequesterName();

        Long getAmountToPay();

        long getReceivableAmount();
    }

    boolean existsByReceivingAccountIdAndStatus(Long accountId, SettlementStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SettlementJpaEntity settlement
               set settlement.receivingAccountId = null
             where settlement.receivingAccountId = :accountId
               and settlement.status <> :draftStatus
            """)
    int detachIssuedByReceivingAccountId(
            @Param("accountId") Long accountId,
            @Param("draftStatus") SettlementStatus draftStatus
    );
}
