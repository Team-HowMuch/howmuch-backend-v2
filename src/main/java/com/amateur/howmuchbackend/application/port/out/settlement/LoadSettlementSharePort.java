package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementSharePort {
    Optional<SettlementShare> findBySettlementIdAndPayerParticipantId(
            UUID settlementId,
            UUID payerParticipantId
    );
}
