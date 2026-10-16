package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import org.springframework.stereotype.Component;

@Component
public class NettingCompressionPolicy {
    public boolean canCompress(int targetCount) {
        return targetCount >= 2;
    }

    public void requireCompressible(int targetCount) {
        if (targetCount == 0)
            throw ApplicationException.of(NettingStatusCode.NETTING_NO_ELIGIBLE_DEBTS);
        if (!canCompress(targetCount))
            throw ApplicationException.of(NettingStatusCode.NETTING_INSUFFICIENT_SETTLEMENTS);
    }
}
