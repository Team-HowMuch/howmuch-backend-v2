package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementDeletionPolicy {
    public void validate(Settlement settlement, boolean hasShares) {
        boolean blocked = switch (settlement.status()) {
            case DRAFT, REQUESTED -> hasShares;
            case AWAITING_DEPOSIT -> true;
            case SUSPENDED, COMPLETED -> false;
        };
        if (blocked) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE);
        }
    }
}
