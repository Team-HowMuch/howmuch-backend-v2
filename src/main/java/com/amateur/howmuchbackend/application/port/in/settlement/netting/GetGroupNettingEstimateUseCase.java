package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult;

public interface GetGroupNettingEstimateUseCase {
    GroupNettingEstimateResult estimate(long groupId);
}
