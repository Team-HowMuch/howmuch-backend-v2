package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementExpenseJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementExpenseJpaRepository
        extends JpaRepository<SettlementExpenseJpaEntity, UUID> {
    List<SettlementExpenseJpaEntity> findAllBySettlementIdInOrderByDisplayOrderAsc(
            Collection<UUID> settlementIds
    );

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

    boolean existsBySettlementIdAndSourceAndSourceReference(
            UUID settlementId,
            SettlementExpenseSource source,
            String sourceReference
    );

    boolean existsBySettlementIdAndSourceAndSourceAccountIdAndSourceReference(
            UUID settlementId,
            SettlementExpenseSource source,
            Long sourceAccountId,
            String sourceReference
    );
}
