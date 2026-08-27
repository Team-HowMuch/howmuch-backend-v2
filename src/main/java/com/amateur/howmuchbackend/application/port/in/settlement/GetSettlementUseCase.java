package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;

import java.util.UUID;

public interface GetSettlementUseCase {
    SettlementDetailResult get(UUID settlementId);
}
