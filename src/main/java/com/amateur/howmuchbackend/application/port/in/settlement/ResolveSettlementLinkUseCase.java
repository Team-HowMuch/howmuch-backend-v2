package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;

public interface ResolveSettlementLinkUseCase {
    SettlementLinkEntryResult resolve(String rawToken);
}
