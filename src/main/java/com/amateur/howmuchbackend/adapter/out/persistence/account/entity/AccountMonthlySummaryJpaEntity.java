package com.amateur.howmuchbackend.adapter.out.persistence.account.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "account_monthly_summaries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountMonthlySummaryJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "year_month", nullable = false)
    private LocalDate yearMonth;

    @Column(name = "monthly_expense_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyExpenseAmount;

    @Column(name = "expense_count", nullable = false)
    private long expenseCount;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
