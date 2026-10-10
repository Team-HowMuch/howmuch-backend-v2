package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SettlementUnpaidSummaryJpaRepository extends Repository<SettlementJpaEntity, UUID> {
    @Query(value = """
            WITH target_settlements AS (
                SELECT id, creator_id, status, allocation_type
                  FROM settlements
                 WHERE creator_id = :creatorId
                   AND status IN ('REQUESTED', 'AWAITING_DEPOSIT')
                   AND deleted_at IS NULL
            ), confirmed_deposits AS (
                SELECT deposit.settlement_id, deposit.share_id,
                       SUM(account_transaction.amount) AS confirmed_amount
                  FROM settlement_deposits deposit
                  JOIN target_settlements target
                    ON target.id = deposit.settlement_id
                  JOIN account_transactions account_transaction
                    ON account_transaction.id = deposit.account_transaction_id
                 GROUP BY deposit.settlement_id, deposit.share_id
            )
            SELECT target.id AS settlementId,
                   target.status AS status,
                   target.allocation_type AS allocationType,
                   participant.id AS participantId,
                   share.amount AS shareAmount,
                   COALESCE(confirmed.confirmed_amount, 0) AS confirmedAmount
              FROM target_settlements target
         LEFT JOIN settlement_participants participant
                ON participant.settlement_id = target.id
               AND (participant.linked_user_id IS NULL
                    OR participant.linked_user_id <> target.creator_id)
         LEFT JOIN settlement_shares share
                ON share.settlement_id = target.id
               AND share.payer_participant_id = participant.id
         LEFT JOIN confirmed_deposits confirmed
                ON confirmed.settlement_id = target.id
               AND confirmed.share_id = share.id
            """, nativeQuery = true)
    List<UnpaidSummaryProjection> findAllByCreatorId(@Param("creatorId") Long creatorId);

    interface UnpaidSummaryProjection {
        UUID getSettlementId();

        String getStatus();

        String getAllocationType();

        UUID getParticipantId();

        Long getShareAmount();

        BigDecimal getConfirmedAmount();
    }
}
