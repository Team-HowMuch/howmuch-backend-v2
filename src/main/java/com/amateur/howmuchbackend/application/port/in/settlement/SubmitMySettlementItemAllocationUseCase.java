package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

public interface SubmitMySettlementItemAllocationUseCase {
    SettlementItemAllocationResult submitMyItems(UUID settlementId);
}
