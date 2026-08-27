package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;

import java.util.UUID;

public interface RequestSettlementUseCase {
    SettlementRequestResult request(UUID settlementId);
}
