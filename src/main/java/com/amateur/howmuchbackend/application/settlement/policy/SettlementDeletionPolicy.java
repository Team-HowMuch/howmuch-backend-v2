package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementDeletionPolicy {
    public void validate(Settlement settlement, boolean hasShares) {
        if (hasShares
                || settlement.status() == SettlementStatus.AWAITING_DEPOSIT
                || settlement.status() == SettlementStatus.COMPLETED) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE);
        }
    }
}
