package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult;

public record GroupNettingEstimateWebResponse(
        long groupId,
        int eligibleSettlementCount,
        int beforeTransferCount,
        int afterTransferCount,
        int reducedTransferCount,
        boolean canCompress) {
    public static GroupNettingEstimateWebResponse from(GroupNettingEstimateResult result) {
        return new GroupNettingEstimateWebResponse(
                result.groupId(),
                result.eligibleSettlementCount(),
                result.beforeTransferCount(),
                result.afterTransferCount(),
                result.reducedTransferCount(),
                result.canCompress());
    }
}
