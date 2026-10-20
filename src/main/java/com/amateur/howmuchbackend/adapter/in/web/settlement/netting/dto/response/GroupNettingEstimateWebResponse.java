package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"groupId", "eligibleSettlementCount", "beforeTransferCount",
        "afterTransferCount", "reducedTransferCount", "canCompress", "reason"})
public record GroupNettingEstimateWebResponse(
        long groupId,
        int eligibleSettlementCount,
        int beforeTransferCount,
        int afterTransferCount,
        int reducedTransferCount,
        boolean canCompress,
        @Schema(description = "압축 불가 사유. PROCESSING(진행 중) 우선, NOT_ENOUGH_TARGETS(대상 2건 미만), NO_REDUCTION(건수 미감소). 압축 가능하면 null", nullable = true)
        Reason reason) {
    @Schema(name = "GroupNettingCompressionUnavailableReason", enumAsRef = true)
    public enum Reason {
        NOT_ENOUGH_TARGETS, NO_REDUCTION, PROCESSING
    }

    public static GroupNettingEstimateWebResponse from(GroupNettingEstimateResult result) {
        return new GroupNettingEstimateWebResponse(
                result.groupId(),
                result.eligibleSettlementCount(),
                result.beforeTransferCount(),
                result.afterTransferCount(),
                result.reducedTransferCount(),
                result.canCompress(),
                result.reason() == null ? null : Reason.valueOf(result.reason().name()));
    }
}
