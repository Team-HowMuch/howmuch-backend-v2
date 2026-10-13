package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRevisionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementRevisionGuard {
    private final LoadSettlementDepositSummaryPort depositSummaryPort;
    private final SettlementRevisionPolicy revisionPolicy;

    public void ensureUnpaid(UUID settlementId) {
        revisionPolicy.validate(!depositSummaryPort
                .findConfirmedShareAmountsBySettlementId(settlementId).isEmpty());
    }
}
