package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LoadTreasurerSettlementsPort {
    List<TreasurerSettlementRecord> findAll(Long userId);

    record TreasurerSettlementRecord(
            UUID settlementId,
            String title,
            SettlementDirection direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            Long amount,
            String counterpartName,
            int payerCount,
            int completedPayerCount
    ) {
    }
}
