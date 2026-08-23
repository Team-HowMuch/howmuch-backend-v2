package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementExpenseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementExpenseJpaRepository
        extends JpaRepository<SettlementExpenseJpaEntity, UUID> {
    List<SettlementExpenseJpaEntity> findAllBySettlementIdOrderByDisplayOrderAsc(
            UUID settlementId
    );

    Optional<SettlementExpenseJpaEntity> findBySettlementIdAndId(
            UUID settlementId,
            UUID expenseId
    );

    Optional<SettlementExpenseJpaEntity> findFirstBySettlementIdOrderByDisplayOrderDesc(
            UUID settlementId
    );

    @Query("""
            select coalesce(sum(expense.amount), 0)
            from SettlementExpenseJpaEntity expense
            where expense.settlementId = :settlementId
            """)
    long sumAmountBySettlementId(@Param("settlementId") UUID settlementId);
}
