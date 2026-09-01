package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SettlementItemAllocationPolicy {
    public void validateSelection(
            SettlementItem item,
            List<SettlementItemSelection> selections,
            UUID participantId,
            SettlementItemAllocationFraction fraction,
            int units
    ) {
        if (item == null || participantId == null || fraction == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
            );
        }
        // 남이 먼저 가져가 남은 몫이 모자란 경우를 별도 코드로 구분한다.
        int requestedUnits = requestedUnits(fraction, units);
        int allocatedUnits = selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .filter(selection -> !selection.participantId().equals(participantId))
                .mapToInt(SettlementItemSelection::allocatedUnits)
                .sum();
        if (allocatedUnits + requestedUnits > item.totalAllocationUnits()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_OVER_ALLOCATED
            );
        }
    }

    private int requestedUnits(SettlementItemAllocationFraction fraction, int units) {
        if (units < SettlementItemSelection.MIN_UNITS) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION_UNITS
            );
        }
        return Math.multiplyExact(fraction.units(), units);
    }

    /**
     * 품목 금액을 선택 단위에 비례해 나눈다. 내림 때문에 남는 1원 단위는 품목이 전부 채워졌을 때만
     * 생기고, 먼저 고른 선택부터 1원씩 부담한다.
     *
     * @return 선택 ID별 금액. 다른 품목의 선택은 담기지 않는다.
     */
    public Map<UUID, Long> calculateSelectionAmounts(
            SettlementItem item,
            List<SettlementItemSelection> selections
    ) {
        List<SettlementItemSelection> itemSelections = selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .sorted(Comparator
                        .comparing(SettlementItemSelection::createdAt)
                        .thenComparing(SettlementItemSelection::id))
                .toList();
        int totalUnits = item.totalAllocationUnits();
        Map<UUID, Long> amountBySelection = new LinkedHashMap<>();
        int allocatedUnits = 0;
        long allocatedAmount = 0L;
        for (SettlementItemSelection selection : itemSelections) {
            int units = selection.allocatedUnits();
            allocatedUnits = Math.addExact(allocatedUnits, units);
            long amount = Math.multiplyExact(item.amount(), (long) units) / totalUnits;
            amountBySelection.put(selection.id(), amount);
            allocatedAmount = Math.addExact(allocatedAmount, amount);
        }

        long remainder = allocatedUnits == totalUnits
                ? Math.subtractExact(item.amount(), allocatedAmount)
                : 0L;
        for (SettlementItemSelection selection : itemSelections) {
            if (remainder <= 0L) {
                break;
            }
            amountBySelection.merge(selection.id(), 1L, Math::addExact);
            remainder--;
        }
        return amountBySelection;
    }

    public long calculatePersonalAmount(
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId
    ) {
        Set<UUID> itemIds = new HashSet<>();
        items.forEach(item -> itemIds.add(item.id()));
        boolean hasUnknownItem = selections.stream()
                .filter(selection -> selection.participantId().equals(participantId))
                .anyMatch(selection -> !itemIds.contains(selection.itemId()));
        if (hasUnknownItem) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
            );
        }

        long total = 0L;
        try {
            for (SettlementItem item : items) {
                Map<UUID, Long> amountBySelection = calculateSelectionAmounts(
                        item, selections
                );
                for (SettlementItemSelection selection : selections) {
                    if (!selection.participantId().equals(participantId)) {
                        continue;
                    }
                    total = Math.addExact(
                            total,
                            amountBySelection.getOrDefault(selection.id(), 0L)
                    );
                }
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL,
                    exception
            );
        }
        return total;
    }

    public Finalization finalizeAllocation(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            LocalDateTime now
    ) {
        AllocationEvaluation evaluation = evaluateAllocation(
                settlementId,
                settlementTotalAmount,
                participants,
                items,
                selections
        );
        if (!evaluation.valid()) {
            return Finalization.invalid(evaluation.invalidItemIds());
        }

        List<SettlementShare> shares = participants.stream()
                .filter(participant -> evaluation.amountByParticipant()
                        .getOrDefault(participant.id(), 0L) > 0L)
                .map(participant -> SettlementShare.create(
                        settlementId,
                        participant.id(),
                        evaluation.amountByParticipant().get(participant.id()),
                        now
                ))
                .toList();
        return Finalization.valid(shares);
    }

    public List<UUID> findInvalidItemIds(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections
    ) {
        return evaluateAllocation(
                settlementId,
                settlementTotalAmount,
                participants,
                items,
                selections
        ).invalidItemIds();
    }

    private AllocationEvaluation evaluateAllocation(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections
    ) {
        Map<UUID, SettlementItem> itemById = new HashMap<>();
        items.forEach(item -> itemById.put(item.id(), item));
        Set<UUID> participantIds = new HashSet<>();
        participants.forEach(participant -> participantIds.add(participant.id()));

        Map<UUID, Integer> allocatedUnitsByItem = new HashMap<>();
        Map<UUID, Long> amountByParticipant = new HashMap<>();
        boolean invalidReferenceOrAmount = false;
        try {
            for (SettlementItemSelection selection : selections) {
                SettlementItem item = itemById.get(selection.itemId());
                if (item == null
                        || !settlementId.equals(selection.settlementId())
                        || !participantIds.contains(selection.participantId())) {
                    invalidReferenceOrAmount = true;
                    continue;
                }
                allocatedUnitsByItem.merge(
                        item.id(), selection.allocatedUnits(), Math::addExact
                );
            }
            for (SettlementItem item : items) {
                Map<UUID, Long> amountBySelection = calculateSelectionAmounts(
                        item, selections
                );
                for (SettlementItemSelection selection : selections) {
                    Long amount = amountBySelection.get(selection.id());
                    if (amount == null) {
                        continue;
                    }
                    amountByParticipant.merge(
                            selection.participantId(), amount, Math::addExact
                    );
                }
            }
        } catch (ApplicationException | ArithmeticException exception) {
            invalidReferenceOrAmount = true;
        }

        List<UUID> invalidItemIds = items.stream()
                .filter(item -> allocatedUnitsByItem.getOrDefault(item.id(), 0)
                        != item.totalAllocationUnits())
                .map(SettlementItem::id)
                .toList();
        if (invalidReferenceOrAmount || !invalidItemIds.isEmpty()) {
            return AllocationEvaluation.invalid(
                    invalidItemIds.isEmpty()
                            ? items.stream().map(SettlementItem::id).toList()
                            : invalidItemIds
            );
        }

        long shareTotal = 0L;
        boolean hasPositiveShare = false;
        try {
            for (SettlementParticipant participant : participants) {
                long amount = amountByParticipant.getOrDefault(participant.id(), 0L);
                if (amount == 0L) {
                    continue;
                }
                hasPositiveShare = true;
                shareTotal = Math.addExact(shareTotal, amount);
            }
        } catch (ArithmeticException exception) {
            return AllocationEvaluation.invalid(
                    items.stream().map(SettlementItem::id).toList()
            );
        }
        if (shareTotal != settlementTotalAmount || !hasPositiveShare) {
            return AllocationEvaluation.invalid(
                    items.stream().map(SettlementItem::id).toList()
            );
        }
        return AllocationEvaluation.valid(amountByParticipant);
    }

    private record AllocationEvaluation(
            boolean valid,
            Map<UUID, Long> amountByParticipant,
            List<UUID> invalidItemIds
    ) {
        private AllocationEvaluation {
            amountByParticipant = Map.copyOf(amountByParticipant);
            invalidItemIds = List.copyOf(invalidItemIds);
        }

        private static AllocationEvaluation valid(
                Map<UUID, Long> amountByParticipant
        ) {
            return new AllocationEvaluation(true, amountByParticipant, List.of());
        }

        private static AllocationEvaluation invalid(List<UUID> invalidItemIds) {
            return new AllocationEvaluation(false, Map.of(), invalidItemIds);
        }
    }

    public record Finalization(
            boolean valid,
            List<SettlementShare> shares,
            List<UUID> invalidItemIds
    ) {
        public Finalization {
            shares = List.copyOf(shares);
            invalidItemIds = List.copyOf(invalidItemIds);
        }

        public static Finalization valid(List<SettlementShare> shares) {
            return new Finalization(true, shares, List.of());
        }

        public static Finalization invalid(List<UUID> invalidItemIds) {
            return new Finalization(false, List.of(), invalidItemIds);
        }
    }
}
