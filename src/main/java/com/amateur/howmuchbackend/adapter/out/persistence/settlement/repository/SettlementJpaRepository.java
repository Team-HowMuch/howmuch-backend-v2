package com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SettlementJpaRepository extends JpaRepository<SettlementJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settlement from SettlementJpaEntity settlement where settlement.id = :settlementId")
    Optional<SettlementJpaEntity> findByIdForUpdate(@Param("settlementId") UUID settlementId);

    boolean existsByReceivingAccountIdAndStatus(Long accountId, SettlementStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SettlementJpaEntity settlement
               set settlement.receivingAccountId = null
             where settlement.receivingAccountId = :accountId
               and settlement.status <> :draftStatus
            """)
    int detachIssuedByReceivingAccountId(
            @Param("accountId") Long accountId,
            @Param("draftStatus") SettlementStatus draftStatus
    );
}
