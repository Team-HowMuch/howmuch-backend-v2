package com.amateur.howmuchbackend.adapter.out.persistence.account.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountMonthlySummaryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AccountMonthlySummaryJpaRepository
        extends JpaRepository<AccountMonthlySummaryJpaEntity, Long> {
    Optional<AccountMonthlySummaryJpaEntity> findByAccount_IdAndYearMonth(Long accountId, LocalDate yearMonth);

    @Modifying
    @Query(value = """
            INSERT INTO account_monthly_summaries (
                account_id, year_month, monthly_expense_amount, expense_count, last_used_at,
                created_at, updated_at
            ) VALUES (
                :accountId, :yearMonth, :monthlyExpenseAmount, :expenseCount, :lastUsedAt,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (account_id, year_month) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("accountId") Long accountId,
            @Param("yearMonth") LocalDate yearMonth,
            @Param("monthlyExpenseAmount") BigDecimal monthlyExpenseAmount,
            @Param("expenseCount") long expenseCount,
            @Param("lastUsedAt") LocalDateTime lastUsedAt
    );
}
