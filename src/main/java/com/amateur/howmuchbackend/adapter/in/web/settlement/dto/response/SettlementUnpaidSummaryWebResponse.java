package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {
        "remainingReceivableAmount", "unpaidParticipantCount", "unconfirmedSettlementCount"
})
public record SettlementUnpaidSummaryWebResponse(
        @Schema(description = "확정 부담액에서 확인 입금을 차감한 남은 받을 금액 합계, 원 단위",
                minimum = "0", example = "7000")
        long remainingReceivableAmount,
        @Schema(description = "미납 참여 건수. 동일 인원의 여러 정산 참여는 각각 집계",
                minimum = "0", example = "1")
        int unpaidParticipantCount,
        @Schema(description = "품목 배분이 완료되지 않은 REQUESTED 정산 건수",
                minimum = "0", example = "0")
        int unconfirmedSettlementCount
) {
    public static SettlementUnpaidSummaryWebResponse from(SettlementUnpaidSummaryResult result) {
        return new SettlementUnpaidSummaryWebResponse(
                result.remainingReceivableAmount(),
                result.unpaidParticipantCount(),
                result.unconfirmedSettlementCount()
        );
    }
}
