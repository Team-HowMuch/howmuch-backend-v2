package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferIntentResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementDepositReconciliationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "송금 버튼 신호의 대조 예약 결과. 입금 확인 결과가 아닙니다.")
public record SettlementTransferIntentWebResponse(UUID settlementId,
        SettlementDepositReconciliationStatus status,
        @Schema(description = "UTC 대조 예약 시각. 이미 처리 중이면 null입니다.") LocalDateTime scheduledAt) {
    public static SettlementTransferIntentWebResponse from(SettlementTransferIntentResult result) {
        return new SettlementTransferIntentWebResponse(result.settlementId(), result.status(), result.scheduledAt());
    }
}
