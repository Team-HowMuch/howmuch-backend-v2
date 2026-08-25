package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementDepositJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementDepositJpaRepository
        extends JpaRepository<SettlementDepositJpaEntity, UUID> {
    List<SettlementDepositJpaEntity> findAllBySettlementIdOrderByMatchedAtAscIdAsc(
            UUID settlementId
    );

    List<SettlementDepositJpaEntity> findAllByAccountTransactionIdIn(
            Collection<Long> transactionIds
    );

    @Query(value = """
            SELECT share.settlement_id AS settlementId,
                   share.id AS shareId,
                   participant.display_name AS payerName,
                   share.amount - COALESCE(SUM(account_transaction.amount), 0) AS remainingAmount,
                   snapshot.created_at AT TIME ZONE 'UTC' AS snapshotCreatedAt
              FROM settlement_shares share
              JOIN settlements settlement
                ON settlement.id = share.settlement_id
              JOIN settlement_participants participant
                ON participant.settlement_id = share.settlement_id
               AND participant.id = share.payer_participant_id
              JOIN settlement_receiving_account_snapshots snapshot
                ON snapshot.settlement_id = share.settlement_id
         LEFT JOIN settlement_deposits deposit
                ON deposit.settlement_id = share.settlement_id
               AND deposit.share_id = share.id
         LEFT JOIN account_transactions account_transaction
                ON account_transaction.id = deposit.account_transaction_id
             WHERE snapshot.source_account_id = :sourceAccountId
               AND settlement.status = 'AWAITING_DEPOSIT'
               AND (participant.linked_user_id IS NULL
                    OR participant.linked_user_id <> settlement.creator_id)
             GROUP BY share.settlement_id, share.id, participant.display_name, share.amount,
                      snapshot.created_at
            HAVING share.amount > COALESCE(SUM(account_transaction.amount), 0)
             ORDER BY share.settlement_id, share.id
            """, nativeQuery = true)
    List<PayableDepositCandidateProjection> findUnfinishedPayableCandidatesBySourceAccountId(
            @Param("sourceAccountId") Long sourceAccountId
    );

    interface PayableDepositCandidateProjection {
        UUID getSettlementId();

        UUID getShareId();

        String getPayerName();

        BigDecimal getRemainingAmount();

        Instant getSnapshotCreatedAt();
    }
}
