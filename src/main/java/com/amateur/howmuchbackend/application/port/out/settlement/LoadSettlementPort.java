package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.Settlement;

import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementPort {
    Optional<Settlement> findById(UUID settlementId);

    Optional<Settlement> findByIdForUpdate(UUID settlementId);
}
