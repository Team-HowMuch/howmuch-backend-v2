package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class SettlementItemAllocationPolicy {
    public void validateSelection(
            SettlementItem item,
            List<SettlementItemSelection> selections,
            UUID participantId,
            SettlementItemAllocationFraction fraction
    ) {
        if (item == null || participantId == null || fraction == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
            );
        }
        fraction.amountOf(item.amount());
        int allocatedUnits = selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .filter(selection -> !selection.participantId().equals(participantId))
                .mapToInt(selection -> selection.fraction().units())
                .sum();
        if (allocatedUnits + fraction.units()
                > SettlementItemAllocationFraction.WHOLE_UNITS) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED
            );
        }
    }

    public long calculatePersonalAmount(
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId
    ) {
        long total = 0L;
        try {
            for (SettlementItemSelection selection : selections) {
                if (!selection.participantId().equals(participantId)) {
                    continue;
                }
                SettlementItem item = items.stream()
                        .filter(candidate -> candidate.id().equals(selection.itemId()))
                        .findFirst()
                        .orElseThrow(() -> ApplicationException.of(
                                SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
                        ));
                total = Math.addExact(total, selection.amountFor(item));
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                    exception
            );
        }
        return total;
    }
}
