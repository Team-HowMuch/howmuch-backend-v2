package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;

import java.util.UUID;

public interface GetSettlementDutchPayAllocationUseCase {
    SettlementDutchPayResult get(UUID settlementId);
}
