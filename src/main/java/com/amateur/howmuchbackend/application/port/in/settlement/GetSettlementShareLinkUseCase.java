package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementShareLinkResult;

import java.util.UUID;

public interface GetSettlementShareLinkUseCase {
    SettlementShareLinkResult get(UUID settlementId);
}
