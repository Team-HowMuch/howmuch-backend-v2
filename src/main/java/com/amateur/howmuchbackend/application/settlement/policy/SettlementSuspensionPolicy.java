package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementSuspensionPolicy {
    public void validate(boolean hasNonCreatorSubmissions) {
        if (hasNonCreatorSubmissions) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE);
        }
    }
}
