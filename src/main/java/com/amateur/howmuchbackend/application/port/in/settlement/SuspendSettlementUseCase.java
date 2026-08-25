package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;

import java.util.UUID;

public interface SuspendSettlementUseCase {
    SettlementResult suspend(UUID settlementId);
}
