package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SettlementRequestPolicy {
    public List<SettlementShare> prepareShares(
            Settlement settlement,
            List<SettlementParticipant> participants,
            List<SettlementExpense> expenses,
            List<SettlementItem> items,
            List<SettlementDutchPayAllocation> dutchPayAllocations,
            LocalDateTime now
    ) {
        validateParticipants(participants);
        long expenseTotal = validateExpenses(settlement, expenses);
        if (settlement.allocationType() == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ALLOCATION_NOT_SELECTED
            );
        }
        return switch (settlement.allocationType()) {
            case DUTCH_PAY -> prepareDutchPayShares(
                    settlement,
                    participants,
                    dutchPayAllocations,
                    now
            );
            case ITEM_ALLOCATION -> {
                long receiptDiscount = expenses.stream()
                        .mapToLong(SettlementExpense::discount)
                        .reduce(0L, Math::addExact);
                validateItems(items, expenseTotal, receiptDiscount);
                yield List.of();
            }
        };
    }

    private void validateParticipants(List<SettlementParticipant> participants) {
        if (participants == null || participants.size() < 2) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REQUEST_PARTICIPANTS_INSUFFICIENT
            );
        }
        Set<UUID> participantIds = new HashSet<>();
        for (SettlementParticipant participant : participants) {
            if (!participantIds.add(participant.id())) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
                );
            }
        }
    }

    private long validateExpenses(
            Settlement settlement,
            List<SettlementExpense> expenses
    ) {
        if (expenses == null || expenses.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_EXPENSE_REQUIRED);
        }
        long total = sumExpenseAmounts(expenses);
        if (total != settlement.totalAmount()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_EXPENSE_TOTAL_MISMATCH
            );
        }
        return total;
    }

    private long sumExpenseAmounts(List<SettlementExpense> expenses) {
        long total = 0L;
        try {
            for (SettlementExpense expense : expenses) {
                total = Math.addExact(total, expense.amount());
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                    exception
            );
        }
        return total;
    }

    private List<SettlementShare> prepareDutchPayShares(
            Settlement settlement,
            List<SettlementParticipant> participants,
            List<SettlementDutchPayAllocation> allocations,
            LocalDateTime now
    ) {
        if (allocations == null || allocations.size() != participants.size()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
            );
        }
        Map<UUID, SettlementDutchPayAllocation> allocationByParticipant = new HashMap<>();
        for (SettlementDutchPayAllocation allocation : allocations) {
            if (!settlement.id().equals(allocation.settlementId())
                    || allocationByParticipant.put(allocation.participantId(), allocation) != null) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
                );
            }
        }

        long total = 0L;
        List<SettlementShare> shares = new ArrayList<>();
        try {
            for (SettlementParticipant participant : participants) {
                SettlementDutchPayAllocation allocation = allocationByParticipant.get(participant.id());
                if (allocation == null) {
                    throw ApplicationException.of(
                            SettlementStatusCode.SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE
                    );
                }
                if (allocation.amount() <= 0) {
                    throw ApplicationException.of(
                            SettlementStatusCode.SETTLEMENT_SHARE_AMOUNT_REQUIRED
                    );
                }
                total = Math.addExact(total, allocation.amount());
                shares.add(SettlementShare.create(
                        settlement.id(), participant.id(), allocation.amount(), now
                ));
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH,
                    exception
            );
        }
        if (total != settlement.totalAmount()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH
            );
        }
        return List.copyOf(shares);
    }

    private void validateItems(
            List<SettlementItem> items, long expenseTotal, long receiptDiscount
    ) {
        if (items == null || items.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ITEM_REQUIRED);
        }
        long itemTotal = 0L;
        try {
            for (SettlementItem item : items) {
                itemTotal = Math.addExact(itemTotal, item.netAmount());
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_TOTAL_MISMATCH,
                    exception
            );
        }
        if (Math.subtractExact(itemTotal, receiptDiscount) != expenseTotal) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_TOTAL_MISMATCH
            );
        }
    }
}
