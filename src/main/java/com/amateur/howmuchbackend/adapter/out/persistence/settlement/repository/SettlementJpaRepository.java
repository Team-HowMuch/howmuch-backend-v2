package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementJpaRepository extends JpaRepository<SettlementJpaEntity, UUID> {
    @Query(value = """
            SELECT
                settlement.id AS settlementId,
                settlement.status AS status,
                COALESCE(link.created_at, settlement.created_at) AS requestedAt,
                CASE WHEN settlement.status = 'COMPLETED' THEN settlement.updated_at AT TIME ZONE 'UTC' END AS completedAt,
                settlement.total_amount AS totalAmount,
                settlement.allocation_type AS allocationType,
                creator.name AS requesterName,
                COUNT(DISTINCT participant.id) AS participantCount,
                COUNT(DISTINCT CASE
                        WHEN participant.linked_user_id IS NULL
                             OR participant.linked_user_id <> settlement.creator_id
                        THEN participant.id
                    END) AS payableParticipantCount,
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
               AND settlement.status <> 'DRAFT'
               AND (
                    CAST(:completedFrom AS timestamptz) IS NULL
                    OR (
                        settlement.status = 'COMPLETED'
                        AND settlement.updated_at >= (CAST(:completedFrom AS timestamptz) AT TIME ZONE 'UTC')
                        AND settlement.updated_at < (CAST(:completedBefore AS timestamptz) AT TIME ZONE 'UTC')
                    )
                )
               AND (
                    :status = 'ALL'
                    OR (
                        :status = 'IN_PROGRESS'
                        AND settlement.status IN (
                            'REQUESTED', 'AWAITING_DEPOSIT', 'SUSPENDED'
                        )
                    )
                    OR settlement.status = :status
                )
               AND (
                    :groupId IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM settlement_participants group_participant
                          JOIN groups filtered_group
                            ON filtered_group.id = group_participant.source_group_id
                           AND filtered_group.status = 'ACTIVE'
                          JOIN group_members viewer_group_member
                            ON viewer_group_member.group_id = filtered_group.id
                           AND viewer_group_member.user_id = :userId
                           AND viewer_group_member.status = 'ACTIVE'
                         WHERE group_participant.settlement_id = settlement.id
                           AND group_participant.source = 'GROUP_MEMBER'
                           AND group_participant.source_group_id = :groupId
                    )
                )
             GROUP BY settlement.id, settlement.status, settlement.created_at, link.created_at,
                      settlement.total_amount, settlement.allocation_type, creator.name
             ORDER BY COALESCE(link.created_at, settlement.created_at) DESC, settlement.id DESC
            """, nativeQuery = true)
    List<SettlementListProjection> findAllForUser(
            @Param("userId") Long userId,
            @Param("tab") String tab,
            @Param("status") String status,
            @Param("groupId") Long groupId,
            @Param("completedFrom") Instant completedFrom,
            @Param("completedBefore") Instant completedBefore
    );

    @Query(value = """
            SELECT DISTINCT
                participant.settlement_id AS settlementId,
                target_group.id AS groupId,
                target_group.name AS groupName,
                target_group.image_file_name AS imageFileName
              FROM settlement_participants participant
              JOIN groups target_group
                ON target_group.id = participant.source_group_id
               AND target_group.status = 'ACTIVE'
              JOIN group_members viewer_group_member
                ON viewer_group_member.group_id = target_group.id
               AND viewer_group_member.user_id = :userId
               AND viewer_group_member.status = 'ACTIVE'
             WHERE participant.source = 'GROUP_MEMBER'
               AND participant.settlement_id IN (:settlementIds)
             ORDER BY participant.settlement_id, target_group.id
            """, nativeQuery = true)
    List<SettlementListGroupProjection> findGroupsForSettlements(
            @Param("settlementIds") List<UUID> settlementIds,
            @Param("userId") Long userId
    );

    @Query(value = """
            SELECT
                settlement.id AS settlementId,
                settlement.title AS title,
                settlement.status AS status,
                settlement.created_at AS requestedAt,
                creator.name AS requesterName,
                settlement.allocation_type AS allocationType,
                MAX(CASE
                        WHEN participant.linked_user_id = :userId THEN share.amount
                    END) AS amountToPay,
                COALESCE(SUM(CASE
                        WHEN participant.linked_user_id IS NULL
                             OR participant.linked_user_id <> settlement.creator_id
                        THEN share.amount
                    END), 0) AS receivableAmount,
                COUNT(DISTINCT CASE
                        WHEN participant.linked_user_id IS NULL
                             OR participant.linked_user_id <> settlement.creator_id
                        THEN participant.id
                    END) AS payerCount,
                COUNT(DISTINCT CASE
                        WHEN (
                                participant.linked_user_id IS NULL
                                OR participant.linked_user_id <> settlement.creator_id
                             )
                             AND paid.share_id IS NOT NULL
                        THEN participant.id
                    END) AS completedPayerCount
              FROM settlements settlement
              JOIN users creator
                ON creator.id = settlement.creator_id
              LEFT JOIN settlement_participants participant
                ON participant.settlement_id = settlement.id
              LEFT JOIN settlement_shares share
                ON share.settlement_id = settlement.id
               AND share.payer_participant_id = participant.id
              LEFT JOIN (
                    SELECT DISTINCT settlement_id, share_id
                      FROM settlement_deposits
                ) paid
                ON paid.settlement_id = settlement.id
               AND paid.share_id = share.id
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
                      settlement.allocation_type, creator.name
             ORDER BY settlement.created_at DESC, settlement.id DESC
            """, nativeQuery = true)
    List<TreasurerSettlementProjection> findAllForTreasurer(
            @Param("userId") Long userId,
            @Param("tab") String tab
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settlement from SettlementJpaEntity settlement where settlement.id = :settlementId")
    Optional<SettlementJpaEntity> findByIdForUpdate(@Param("settlementId") UUID settlementId);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM settlements settlement
                 WHERE settlement.status <> 'COMPLETED'
                   AND (
                        settlement.creator_id = :userId
                        OR EXISTS (
                            SELECT 1
                              FROM settlement_participants participant
                             WHERE participant.settlement_id = settlement.id
                               AND participant.linked_user_id = :userId
                        )
                   )
            )
            """, nativeQuery = true)
    boolean existsByUserIdAndStatusNotCompleted(@Param("userId") Long userId);

    interface SettlementListProjection {
        UUID getSettlementId();

        String getStatus();

        LocalDateTime getRequestedAt();

        Instant getCompletedAt();

        long getTotalAmount();

        String getRequesterName();

        long getParticipantCount();

        long getPayableParticipantCount();

        Long getCompletedParticipantCount();

        Long getAmountToPay();

        String getAllocationType();
    }

    interface SettlementListGroupProjection {
        UUID getSettlementId();

        Long getGroupId();

        String getGroupName();

        String getImageFileName();
    }

    interface TreasurerSettlementProjection {
        UUID getSettlementId();

        String getTitle();

        String getStatus();

        LocalDateTime getRequestedAt();

        String getRequesterName();

        String getAllocationType();

        Long getAmountToPay();

        long getReceivableAmount();

        long getPayerCount();

        long getCompletedPayerCount();
    }

    boolean existsByReceivingAccountIdAndStatusNot(Long accountId, SettlementStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SettlementJpaEntity settlement
               set settlement.receivingAccountId = null
             where settlement.receivingAccountId = :accountId
               and settlement.status = :completedStatus
            """)
    int detachCompletedByReceivingAccountId(
            @Param("accountId") Long accountId,
            @Param("completedStatus") SettlementStatus completedStatus
    );
}
