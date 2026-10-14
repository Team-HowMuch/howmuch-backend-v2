package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult.*;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.springframework.stereotype.Component;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SettlementWidgetSummaryPolicy {
    public SettlementWidgetSummaryResult summarize(List<SettlementWidgetReadModel> facts,
            Map<UUID, Long> requestedReceivables, LocalDateTime lastReconciledAt) {
        Map<UUID, List<SettlementWidgetReadModel>> grouped = new LinkedHashMap<>();
        facts.forEach(fact -> grouped.computeIfAbsent(fact.settlementId(), ignored -> new ArrayList<>()).add(fact));
        List<ReceivableSettlement> receivable = new ArrayList<>();
        List<PayableSettlement> payable = new ArrayList<>();
        for (var rows : grouped.values()) {
            var first = rows.getFirst();
            if (first.creator()) {
                long remaining = 0;
                long confirmed = 0;
                int unpaid = 0;
                boolean unconfirmed = first.status() == SettlementStatus.REQUESTED
                        && first.allocationType() == SettlementAllocationType.ITEM_ALLOCATION;
                for (var row : rows) {
                    if (row.participantId() == null) continue;
                    if (row.shareAmount() != null) {
                        long balance = remaining(row);
                        remaining = Math.addExact(remaining, balance);
                        confirmed = Math.addExact(confirmed, row.shareAmount() - balance);
                        if (balance > 0) unpaid = Math.incrementExact(unpaid);
                    } else if (!row.submitted() || row.receiptAdjustmentPending()) {
                        unpaid = Math.incrementExact(unpaid);
                    }
                }
                if (unconfirmed) {
                    remaining = Math.max(0L, Math.subtractExact(requestedReceivables.get(first.settlementId()), confirmed));
                }
                receivable.add(new ReceivableSettlement(first.settlementId(), first.title(), remaining, unpaid, unconfirmed));
            } else {
                Long remaining = null;
                if (first.shareAmount() != null) {
                    remaining = remaining(first);
                } else if (first.submitted() && !first.receiptAdjustmentPending()) {
                    remaining = 0L;
                }
                PayableStatus status = remaining != null && remaining > 0 ? PayableStatus.PAYABLE
                        : first.allocationType() == SettlementAllocationType.ITEM_ALLOCATION && !first.submitted()
                                && first.shareAmount() == null ? PayableStatus.SELECTION_REQUIRED : PayableStatus.WAITING;
                payable.add(new PayableSettlement(first.settlementId(), first.title(), remaining, status));
            }
        }
        return new SettlementWidgetSummaryResult(
                new ReceivableSummary(receivable.stream().mapToLong(ReceivableSettlement::remainingAmount)
                        .reduce(0L, Math::addExact), receivable.size(), List.copyOf(receivable)),
                new PayableSummary(payable.stream().filter(row -> row.remainingAmount() != null)
                        .mapToLong(PayableSettlement::remainingAmount).reduce(0L, Math::addExact),
                        payable.size(), List.copyOf(payable)), lastReconciledAt);
    }

    private long remaining(SettlementWidgetReadModel row) {
        return BigInteger.valueOf(row.shareAmount()).subtract(row.confirmedAmount()).max(BigInteger.ZERO).longValueExact();
    }
}
