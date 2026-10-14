package com.amateur.howmuchbackend.application.port.in.settlement.result;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SettlementWidgetSummaryResult(
        ReceivableSummary receivable,
        PayableSummary payable,
        LocalDateTime lastReconciledAt
) {
    public record ReceivableSummary(long totalAmount, int totalCount, List<ReceivableSettlement> settlements) {}
    public record PayableSummary(long totalAmount, int totalCount, List<PayableSettlement> settlements) {}
    public record ReceivableSettlement(UUID settlementId, String title, long remainingAmount,
                                       int unpaidParticipantCount, boolean amountUnconfirmed) {}
    public record PayableSettlement(UUID settlementId, String title, Long remainingAmount, PayableStatus status) {}
    public enum PayableStatus { PAYABLE, SELECTION_REQUIRED, WAITING }
}
