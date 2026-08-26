package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LoadTreasurerSettlementsPort {
    List<TreasurerSettlementRecord> findAll(Long userId);

    record TreasurerSettlementRecord(
            UUID settlementId,
            String title,
            SettlementListTab direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            Long amount,
            String counterpartName
    ) {
    }
}
