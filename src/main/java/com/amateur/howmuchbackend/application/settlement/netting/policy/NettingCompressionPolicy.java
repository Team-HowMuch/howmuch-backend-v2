package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult.Reason;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import org.springframework.stereotype.Component;

@Component
public class NettingCompressionPolicy {
    public boolean canCompress(int targetCount) {
        return targetCount >= 2;
    }

    public Reason unavailableReason(int targetCount, int before, int after, boolean processing) {
        if (processing) return Reason.PROCESSING;
        if (!canCompress(targetCount)) return Reason.NOT_ENOUGH_TARGETS;
        if (before <= after) return Reason.NO_REDUCTION;
        return null;
    }

    public void requireCompressible(int targetCount) {
        if (targetCount == 0)
            throw ApplicationException.of(NettingStatusCode.NETTING_NO_ELIGIBLE_DEBTS);
        if (!canCompress(targetCount))
            throw ApplicationException.of(NettingStatusCode.NETTING_INSUFFICIENT_SETTLEMENTS);
    }
}
