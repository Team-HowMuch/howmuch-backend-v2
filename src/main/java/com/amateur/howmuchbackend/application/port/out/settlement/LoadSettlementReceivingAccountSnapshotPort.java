package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface LoadSettlementReceivingAccountSnapshotPort {
    Optional<SettlementReceivingAccountSnapshot> findBySettlementId(UUID settlementId);
}
