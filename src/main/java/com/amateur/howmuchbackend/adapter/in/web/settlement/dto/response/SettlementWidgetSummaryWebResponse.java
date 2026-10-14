package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementWidgetSummaryWebResponse(
        ReceivableSummary receivable,
        PayableSummary payable,
        @Schema(description = "마지막 성공한 위젯 전체 입금 대조의 조회 기준 UTC 시각. 기록이 없으면 null")
        LocalDateTime lastReconciledAt
) {
    public record ReceivableSummary(long totalAmount, int totalCount, List<ReceivableSettlement> settlements) {}
    public record PayableSummary(long totalAmount, int totalCount, List<PayableSettlement> settlements) {}
    public record ReceivableSettlement(UUID settlementId, String title, long remainingAmount,
                                       int unpaidParticipantCount, boolean amountUnconfirmed) {}
    public record PayableSettlement(UUID settlementId, String title,
            @Schema(description = "확정 Share 잔액. 미확정이면 null", nullable = true) Long remainingAmount,
            @Schema(allowableValues = {"PAYABLE", "SELECTION_REQUIRED", "WAITING"}) String status) {}

    public static SettlementWidgetSummaryWebResponse from(SettlementWidgetSummaryResult result) {
        return new SettlementWidgetSummaryWebResponse(
                new ReceivableSummary(result.receivable().totalAmount(), result.receivable().totalCount(),
                        result.receivable().settlements().stream().map(row -> new ReceivableSettlement(
                                row.settlementId(), row.title(), row.remainingAmount(), row.unpaidParticipantCount(),
                                row.amountUnconfirmed())).toList()),
                new PayableSummary(result.payable().totalAmount(), result.payable().totalCount(),
                        result.payable().settlements().stream().map(row -> new PayableSettlement(
                                row.settlementId(), row.title(), row.remainingAmount(), row.status().name())).toList()),
                result.lastReconciledAt());
    }
}
