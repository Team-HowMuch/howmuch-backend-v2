package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;
import java.util.UUID;

public interface LoadSettlementDepositSummaryPort {
    List<ConfirmedShareAmount> findConfirmedShareAmountsBySettlementId(UUID settlementId);

    record ConfirmedShareAmount(UUID shareId, long amount) {
    }
}
