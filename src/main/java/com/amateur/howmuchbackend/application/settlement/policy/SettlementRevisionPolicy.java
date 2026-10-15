package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementRevisionPolicy {
    public void validate(boolean hasConfirmedPayments) {
        if (hasConfirmedPayments) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_REVISION_PAYMENT_EXISTS);
        }
    }
}
