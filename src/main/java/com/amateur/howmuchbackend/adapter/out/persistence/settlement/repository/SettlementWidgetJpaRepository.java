package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface SettlementWidgetJpaRepository extends Repository<SettlementJpaEntity, UUID> {
    @Query(value = """
            WITH targets AS (
                SELECT s.* FROM settlements s
                WHERE s.status IN ('REQUESTED', 'AWAITING_DEPOSIT') AND s.deleted_at IS NULL
                  AND (s.creator_id = :userId OR EXISTS (
                      SELECT 1 FROM settlement_participants p
                      WHERE p.settlement_id = s.id AND p.linked_user_id = :userId
                  ))
            ), confirmed AS (
                SELECT d.settlement_id, d.share_id, SUM(t.amount) AS amount
                  FROM settlement_deposits d
                  JOIN targets s ON s.id = d.settlement_id
                  JOIN account_transactions t ON t.id = d.account_transaction_id
                 GROUP BY d.settlement_id, d.share_id
            )
            SELECT s.id AS settlementId, s.title AS title, s.total_amount AS totalAmount,
                   (s.creator_id = :userId) AS creator, s.status AS status,
                   s.allocation_type AS allocationType, cp.id AS creatorParticipantId,
                   p.id AS participantId, sh.amount AS shareAmount,
                   COALESCE(c.amount, 0) AS confirmedAmount,
                   EXISTS (SELECT 1 FROM settlement_item_submissions sub
                            WHERE sub.settlement_id = s.id AND sub.participant_id = p.id) AS submitted,
                   (s.status = 'REQUESTED' AND s.allocation_type = 'ITEM_ALLOCATION'
                    AND EXISTS (SELECT 1 FROM settlement_expenses e
                                WHERE e.settlement_id = s.id AND (e.discount > 0 OR e.delivery_fee > 0)))
                       AS receiptAdjustmentPending
              FROM targets s
         LEFT JOIN settlement_participants cp ON cp.settlement_id = s.id AND cp.linked_user_id = s.creator_id
         LEFT JOIN settlement_participants p ON p.settlement_id = s.id
               AND ((s.creator_id = :userId AND (p.linked_user_id IS NULL OR p.linked_user_id <> :userId))
                    OR (s.creator_id <> :userId AND p.linked_user_id = :userId))
         LEFT JOIN settlement_shares sh ON sh.settlement_id = s.id AND sh.payer_participant_id = p.id
         LEFT JOIN confirmed c ON c.settlement_id = s.id AND c.share_id = sh.id
             ORDER BY s.created_at DESC, s.id, p.display_order
            """, nativeQuery = true)
    List<WidgetProjection> findAllInProgress(@Param("userId") Long userId);

    interface WidgetProjection {
        UUID getSettlementId();
        String getTitle();
        Long getTotalAmount();
        boolean getCreator();
        String getStatus();
        String getAllocationType();
        UUID getCreatorParticipantId();
        UUID getParticipantId();
        Long getShareAmount();
        BigDecimal getConfirmedAmount();
        boolean getSubmitted();
        boolean getReceiptAdjustmentPending();
    }
}
