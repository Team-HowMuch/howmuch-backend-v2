package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementSharePort {
    boolean existsBySettlementId(UUID settlementId);

    List<SettlementShare> findAllBySettlementId(UUID settlementId);

    Optional<SettlementShare> findBySettlementIdAndPayerParticipantId(
            UUID settlementId,
            UUID payerParticipantId
    );
}
