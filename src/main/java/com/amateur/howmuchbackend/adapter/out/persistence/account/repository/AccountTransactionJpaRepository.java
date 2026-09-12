package com.amateur.howmuchbackend.adapter.out.persistence.account.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountTransactionJpaEntity;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountTransactionJpaRepository extends JpaRepository<AccountTransactionJpaEntity, Long> {
    List<AccountTransactionJpaEntity>
    findByAccountIdAndTransactedAtGreaterThanEqualAndTransactedAtLessThanOrderByTransactedAtDescIdDesc(
            Long accountId,
            LocalDateTime start,
            LocalDateTime end
    );

    Optional<AccountTransactionJpaEntity> findByAccountIdAndId(Long accountId, Long transactionId);

    @Query(value = """
            SELECT link.external_transaction_key AS "externalTransactionKey",
                   settlement.id AS "settlementId",
                   settlement.title AS title,
                   settlement.status AS status
              FROM (
                    SELECT expense.source_reference AS external_transaction_key,
                           expense.settlement_id
                      FROM settlement_expenses expense
                     WHERE expense.source = 'PAYMENT_HISTORY'
                       AND expense.source_account_id = :accountId
                       AND expense.source_reference IN (:externalTransactionKeys)
                    UNION
                    SELECT tx.external_transaction_key,
                           deposit.settlement_id
                      FROM settlement_deposits deposit
                      JOIN account_transactions tx
                        ON tx.id = deposit.account_transaction_id
                       AND tx.account_id = deposit.source_account_id
                     WHERE deposit.source_account_id = :accountId
                       AND tx.external_transaction_key IN (:externalTransactionKeys)
                    UNION
                    SELECT tx.external_transaction_key,
                           payment.settlement_id
                      FROM settlement_payments payment
                      JOIN account_transactions tx
                        ON tx.id = payment.account_transaction_id
                       AND tx.account_id = payment.payer_account_id
                     WHERE payment.payer_account_id = :accountId
                       AND tx.external_transaction_key IN (:externalTransactionKeys)
                   ) link
              JOIN settlements settlement ON settlement.id = link.settlement_id
             WHERE settlement.status <> 'DRAFT'
             ORDER BY link.external_transaction_key,
                      settlement.created_at DESC,
                      settlement.id DESC
            """, nativeQuery = true)
    List<LinkedSettlementProjection> findLinkedSettlements(
            @Param("accountId") Long accountId,
            @Param("externalTransactionKeys") Collection<String> externalTransactionKeys
    );

    List<AccountTransactionJpaEntity>
    findByAccountIdAndTypeAndTransactedAtGreaterThanEqualAndTransactedAtLessThanEqualOrderByTransactedAtAscIdAsc(
            Long accountId,
            AccountTransactionType type,
            LocalDateTime start,
            LocalDateTime end
    );

    @Query("""
            select transaction.accountId, max(transaction.transactedAt)
              from AccountTransactionJpaEntity transaction
             where transaction.accountId in :accountIds
             group by transaction.accountId
            """)
    List<Object[]> findLastUsedAtByAccountIds(@Param("accountIds") Collection<Long> accountIds);

    @Query(value = """
            SELECT COALESCE(SUM(amount) FILTER (WHERE transaction_type = 'EXPENSE'), 0) AS monthlyExpenseAmount,
                   COUNT(*) FILTER (WHERE transaction_type = 'EXPENSE') AS expenseCount,
                   MAX(transacted_at) AS lastUsedAt
              FROM account_transactions
             WHERE account_id = :accountId
               AND transacted_at >= :start
               AND transacted_at < :end
            """, nativeQuery = true)
    MonthlyExpenseProjection calculateSummary(
            @Param("accountId") Long accountId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Modifying
    @Query(value = """
            INSERT INTO account_transactions (
                account_id, external_transaction_key, transaction_type, amount, transacted_at,
                description, after_balance, created_at, updated_at
            ) VALUES (
                :accountId, :externalTransactionKey, :transactionType, :amount, :transactedAt,
                :description, :afterBalance, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (account_id, external_transaction_key) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("accountId") Long accountId,
            @Param("externalTransactionKey") String externalTransactionKey,
            @Param("transactionType") String transactionType,
            @Param("amount") BigDecimal amount,
            @Param("transactedAt") LocalDateTime transactedAt,
            @Param("description") String description,
            @Param("afterBalance") BigDecimal afterBalance
    );

    interface MonthlyExpenseProjection {
        BigDecimal getMonthlyExpenseAmount();

        Long getExpenseCount();

        LocalDateTime getLastUsedAt();
    }

    interface LinkedSettlementProjection {
        String getExternalTransactionKey();

        UUID getSettlementId();

        String getTitle();

        String getStatus();
    }
}
