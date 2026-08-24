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
import java.util.HashMap;
import java.util.HashSet;
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
                        item.id(), selection.fraction().units(), Math::addExact
                );
                amountByParticipant.merge(
                        selection.participantId(),
                        selection.amountFor(item),
                        Math::addExact
                );
            }
        } catch (ApplicationException | ArithmeticException exception) {
            invalidReferenceOrAmount = true;
        }

        List<UUID> invalidItemIds = items.stream()
                .filter(item -> allocatedUnitsByItem.getOrDefault(item.id(), 0)
                        != SettlementItemAllocationFraction.WHOLE_UNITS)
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
