package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

public interface RemoveMySettlementItemSelectionUseCase {
    SettlementItemAllocationResult removeMyItem(UUID settlementId, UUID itemId);
}
