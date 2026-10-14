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

    @Query(value = """
            SELECT deposit.share_id AS shareId,
                   SUM(account_transaction.amount) AS amount
              FROM settlement_deposits deposit
              JOIN account_transactions account_transaction
                ON account_transaction.id = deposit.account_transaction_id
             WHERE deposit.settlement_id = :settlementId
             GROUP BY deposit.share_id
            """, nativeQuery = true)
    List<ConfirmedShareAmountProjection> findConfirmedShareAmountsBySettlementId(
            @Param("settlementId") UUID settlementId
    );

    @Query(value = """
            SELECT account_transaction.external_transaction_key AS externalTransactionKey,
                   snapshot.settlement_id AS settlementId,
                   snapshot.source_account_id AS sourceAccountId,
                   snapshot.account_number_ciphertext AS accountNumberCiphertext
              FROM settlement_deposits deposit
              JOIN account_transactions account_transaction
                ON account_transaction.id = deposit.account_transaction_id
              JOIN settlement_receiving_account_snapshots snapshot
                ON snapshot.settlement_id = deposit.settlement_id
               AND snapshot.source_account_id = deposit.source_account_id
              JOIN settlements settlement
                ON settlement.id = deposit.settlement_id
             WHERE settlement.creator_id = :creatorId
               AND snapshot.organization = :organization
               AND account_transaction.external_transaction_key IN (:externalTransactionKeys)
            """, nativeQuery = true)
    List<MatchedTransactionProjection> findMatchedTransactionsByCreatorIdAndOrganization(
            @Param("creatorId") Long creatorId,
            @Param("organization") String organization,
            @Param("externalTransactionKeys") Collection<String> externalTransactionKeys
    );

    @Query(value = """
            SELECT share.settlement_id AS settlementId,
                   share.id AS shareId,
                   participant.display_name AS payerName,
                   share.amount - COALESCE(SUM(account_transaction.amount), 0) AS remainingAmount,
                   snapshot.created_at AT TIME ZONE 'UTC' AS snapshotCreatedAt,
                   snapshot.source_account_id AS sourceAccountId,
                   snapshot.account_number_ciphertext AS accountNumberCiphertext
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
             WHERE settlement.creator_id = :creatorId
               AND snapshot.organization = :organization
               AND (settlement.status = 'AWAITING_DEPOSIT'
                    OR ((:includeAllRequested = TRUE OR settlement.id = :targetSettlementId)
                        AND settlement.status = 'REQUESTED'))
               AND settlement.deleted_at IS NULL
               AND (participant.linked_user_id IS NULL
                    OR participant.linked_user_id <> settlement.creator_id)
             GROUP BY share.settlement_id, share.id, participant.display_name, share.amount,
                      snapshot.created_at, snapshot.source_account_id,
                      snapshot.account_number_ciphertext
            HAVING share.amount > COALESCE(SUM(account_transaction.amount), 0)
             ORDER BY share.settlement_id, share.id
            """, nativeQuery = true)
    List<PayableDepositCandidateProjection> findUnfinishedPayableCandidatesByCreatorIdAndOrganizationAndTargetSettlementId(
            @Param("creatorId") Long creatorId,
            @Param("organization") String organization,
            @Param("targetSettlementId") UUID targetSettlementId,
            @Param("includeAllRequested") boolean includeAllRequested
    );

    interface MatchedTransactionProjection {
        String getExternalTransactionKey();

        UUID getSettlementId();

        Long getSourceAccountId();

        String getAccountNumberCiphertext();
    }

    interface ConfirmedShareAmountProjection {
        UUID getShareId();

        BigDecimal getAmount();
    }

    interface PayableDepositCandidateProjection {
        UUID getSettlementId();

        UUID getShareId();

        String getPayerName();

        BigDecimal getRemainingAmount();

        Instant getSnapshotCreatedAt();

        Long getSourceAccountId();

        String getAccountNumberCiphertext();
    }
}
