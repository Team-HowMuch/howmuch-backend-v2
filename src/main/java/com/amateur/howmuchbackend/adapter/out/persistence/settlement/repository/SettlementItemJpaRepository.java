package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SettlementItemJpaRepository extends JpaRepository<SettlementItemJpaEntity, UUID> {
    @Modifying(flushAutomatically = true)
    @Query("delete from SettlementItemJpaEntity item where item.settlementId = :settlementId and item.expenseId = :expenseId")
    void deleteByExpenseId(@Param("settlementId") UUID settlementId, @Param("expenseId") UUID expenseId);

    @Query("""
            select item
            from SettlementItemJpaEntity item, SettlementExpenseJpaEntity expense
            where item.settlementId = :settlementId
              and expense.id = item.expenseId
              and expense.settlementId = item.settlementId
            order by expense.displayOrder asc, item.displayOrder asc, item.id asc
            """)
    List<SettlementItemJpaEntity> findAllOrderedBySettlementId(
            @Param("settlementId") UUID settlementId
    );

    /** 여러 정산의 품목을 한 번에 읽는다. 정산 안의 순서는 {@link #findAllOrderedBySettlementId}와 같다. */
    @Query("""
            select item
            from SettlementItemJpaEntity item, SettlementExpenseJpaEntity expense
            where item.settlementId in :settlementIds
              and expense.id = item.expenseId
              and expense.settlementId = item.settlementId
            order by item.settlementId asc, expense.displayOrder asc, item.displayOrder asc, item.id asc
            """)
    List<SettlementItemJpaEntity> findAllOrderedBySettlementIdIn(
            @Param("settlementIds") Collection<UUID> settlementIds
    );

}
