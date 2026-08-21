package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;

import java.util.UUID;

public interface GetSettlementUseCase {
    SettlementResult get(UUID settlementId);
}
