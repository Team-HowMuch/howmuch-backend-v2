package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.UUID;

public interface DeleteSettlementSharePort {
    void deleteAllBySettlementId(UUID settlementId);
}
