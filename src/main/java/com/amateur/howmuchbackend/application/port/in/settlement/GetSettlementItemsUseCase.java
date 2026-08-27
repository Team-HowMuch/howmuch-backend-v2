package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;

import java.util.UUID;

public interface GetSettlementItemsUseCase {
    SettlementItemListResult get(UUID settlementId);
}
