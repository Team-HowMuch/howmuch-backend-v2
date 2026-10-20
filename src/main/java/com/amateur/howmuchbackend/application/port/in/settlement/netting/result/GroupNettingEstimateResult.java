package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

public record GroupNettingEstimateResult(
        long groupId,
        int eligibleSettlementCount,
        int beforeTransferCount,
        int afterTransferCount,
        int reducedTransferCount,
        boolean canCompress,
        Reason reason) {
    public enum Reason {
        NOT_ENOUGH_TARGETS, NO_REDUCTION, PROCESSING
    }
}
