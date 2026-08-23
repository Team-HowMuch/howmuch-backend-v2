package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementItemJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SettlementItemJpaRepository extends JpaRepository<SettlementItemJpaEntity, UUID> {
    @Query("""
            select item
            from SettlementItemJpaEntity item
            where item.settlementId = :settlementId
            order by item.createdAt asc, item.displayOrder asc, item.id asc
            """)
    List<SettlementItemJpaEntity> findAllOrderedBySettlementId(
            @Param("settlementId") UUID settlementId
    );

}
