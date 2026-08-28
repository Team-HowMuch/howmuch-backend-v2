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
                COALESCE(link.created_at, settlement.created_at) AT TIME ZONE 'UTC' AS requestedAt,
                settlement.total_amount AS totalAmount,
                creator.name AS requesterName,
                COUNT(DISTINCT participant.id) AS participantCount,
                MAX(payment_summary.payable_participant_count) AS payableParticipantCount,
                MAX(payment_summary.completed_participant_count) AS completedParticipantCount,
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
              LEFT JOIN settlement_links link
                ON link.settlement_id = settlement.id
              LEFT JOIN (
                    SELECT payable_share.settlement_id,
                           COUNT(*) AS payable_participant_count,
                           COUNT(*) FILTER (
                               WHERE COALESCE(confirmed.confirmed_amount, 0)
                                     >= payable_share.amount
                           ) AS completed_participant_count
                      FROM settlement_shares payable_share
                      JOIN settlement_participants payer
                        ON payer.settlement_id = payable_share.settlement_id
                       AND payer.id = payable_share.payer_participant_id
                      JOIN settlements payable_settlement
                        ON payable_settlement.id = payable_share.settlement_id
                 LEFT JOIN (
                            SELECT deposit.settlement_id,
                                   deposit.share_id,
                                   SUM(account_transaction.amount) AS confirmed_amount
                              FROM settlement_deposits deposit
                              JOIN account_transactions account_transaction
                                ON account_transaction.id = deposit.account_transaction_id
                             GROUP BY deposit.settlement_id, deposit.share_id
                           ) confirmed
                        ON confirmed.settlement_id = payable_share.settlement_id
                       AND confirmed.share_id = payable_share.id
                     WHERE payer.linked_user_id IS NULL
                        OR payer.linked_user_id <> payable_settlement.creator_id
                     GROUP BY payable_share.settlement_id
                ) payment_summary
                ON payment_summary.settlement_id = settlement.id
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
             GROUP BY settlement.id, settlement.status, settlement.created_at, link.created_at,
                      settlement.total_amount, creator.name
             ORDER BY COALESCE(link.created_at, settlement.created_at) DESC, settlement.id DESC
            """, nativeQuery = true)
    List<SettlementListProjection> findAllForUser(
            @Param("userId") Long userId,
            @Param("tab") String tab,
            @Param("status") String status
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

        Long getPayableParticipantCount();

        Long getCompletedParticipantCount();

        Long getAmountToPay();
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
