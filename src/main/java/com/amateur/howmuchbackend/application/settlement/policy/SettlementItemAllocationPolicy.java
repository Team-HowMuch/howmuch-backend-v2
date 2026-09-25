package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * 품목 금액을 선택 단위에 비례해 나눈다. 항상 내림이라 다른 참여자의 선택과 무관하다.
     *
     * <p>내림 때문에 선택 금액의 합이 품목 금액보다 품목당 최대 (선택 수 - 1)원 적을 수 있다.
     * 그 차액은 생성자가 부담한다. 나머지를 나눠 얹으려면 품목이 다 찰 때까지 기다려야 하고,
     * 그러면 참여자가 자기 몫을 제출하고도 송금하지 못한다.
     *
     * @return 선택 ID별 금액. 다른 품목의 선택은 담기지 않는다.
     */
    public Map<UUID, Long> calculateSelectionAmounts(
            SettlementItem item,
            List<SettlementItemSelection> selections
    ) {
        int totalUnits = item.totalAllocationUnits();
        Map<UUID, Long> amountBySelection = new LinkedHashMap<>();
        selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .sorted(Comparator
                        .comparing(SettlementItemSelection::createdAt)
                        .thenComparing(SettlementItemSelection::id))
                .forEach(selection -> amountBySelection.put(
                        selection.id(),
                        Math.multiplyExact(
                                item.netAmount(),
                                (long) selection.allocatedUnits()
                        ) / totalUnits
                ));
        return amountBySelection;
    }

    public long calculatePersonalAmount(
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId
    ) {
        return calculatePersonalAmount(items, selections, participantId, 0);
    }

    public long calculatePersonalAmount(
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId,
            long receiptDiscount
    ) {
        return Math.subtractExact(calculateBaseAmount(items, selections, participantId),
                calculateDiscountShare(items, selections, participantId, receiptDiscount));
    }

    private long calculateBaseAmount(
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

    public long calculateDiscountShare(
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId,
            long receiptDiscount
    ) {
        if (receiptDiscount == 0) {
            return 0;
        }
        Map<UUID, Long> baseAmounts = new HashMap<>();
        for (SettlementItemSelection selection : selections) {
            baseAmounts.putIfAbsent(selection.participantId(), 0L);
        }
        for (UUID id : new ArrayList<>(baseAmounts.keySet())) {
            baseAmounts.put(id, calculateBaseAmount(items, selections, id));
        }
        long itemTotal = items.stream().mapToLong(SettlementItem::netAmount)
                .reduce(0L, Math::addExact);
        if (receiptDiscount < 0 || receiptDiscount > itemTotal) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL);
        }
        long allocatedTotal = baseAmounts.values().stream().mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
        if (fullyAllocated(items, selections) && receiptDiscount <= allocatedTotal) {
            return distributeDiscount(baseAmounts, receiptDiscount)
                    .getOrDefault(participantId, 0L);
        }
        long base = baseAmounts.getOrDefault(participantId, 0L);
        return BigInteger.valueOf(receiptDiscount).multiply(BigInteger.valueOf(base))
                .divide(BigInteger.valueOf(itemTotal)).longValueExact();
    }

    private boolean fullyAllocated(
            List<SettlementItem> items, List<SettlementItemSelection> selections
    ) {
        return items.stream().allMatch(item -> selections.stream()
                .filter(selection -> selection.itemId().equals(item.id()))
                .mapToInt(SettlementItemSelection::allocatedUnits)
                .sum() == item.totalAllocationUnits());
    }

    /** 전체 품목 몫이 확정된 뒤 최대 잔여법으로 할인액을 원 단위까지 나눈다. */
    public Map<UUID, Long> distributeDiscount(
            Map<UUID, Long> baseAmounts, long receiptDiscount
    ) {
        long total = baseAmounts.values().stream().mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
        if (receiptDiscount < 0 || receiptDiscount > total) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL);
        }
        if (receiptDiscount == 0 || total == 0) {
            return Map.of();
        }
        BigInteger divisor = BigInteger.valueOf(total);
        Map<UUID, Long> shares = new HashMap<>();
        Map<UUID, BigInteger> remainders = new HashMap<>();
        long assigned = 0;
        for (var entry : baseAmounts.entrySet()) {
            if (entry.getValue() < 0) {
                throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL);
            }
            BigInteger[] division = BigInteger.valueOf(receiptDiscount)
                    .multiply(BigInteger.valueOf(entry.getValue()))
                    .divideAndRemainder(divisor);
            long share = division[0].longValueExact();
            shares.put(entry.getKey(), share);
            remainders.put(entry.getKey(), division[1]);
            assigned = Math.addExact(assigned, share);
        }
        List<UUID> remainderOrder = remainders.keySet().stream()
                .filter(id -> baseAmounts.get(id) > 0)
                .sorted(Comparator.<UUID, BigInteger>comparing(remainders::get)
                        .reversed().thenComparing(UUID::compareTo))
                .toList();
        long remaining = receiptDiscount - assigned;
        for (int index = 0; index < remaining; index++) {
            UUID id = remainderOrder.get(index);
            shares.put(id, shares.get(id) + 1);
        }
        return Map.copyOf(shares);
    }

    /**
     * 참여자 한 명의 Share를 만든다. 금액이 자기 선택만으로 정해지므로
     * 다른 참여자를 기다리지 않고 제출 시점에 확정할 수 있다.
     *
     * @return 고른 몫이 없으면(금액 0) 빈 값
     */
    public Optional<SettlementShare> buildShareFor(
            UUID settlementId,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            UUID participantId,
            LocalDateTime now
    ) {
        long amount = calculatePersonalAmount(items, selections, participantId);

        return amount > 0L
                ? Optional.of(SettlementShare.create(settlementId, participantId, amount, now))
                : Optional.empty();
    }

    public Finalization finalizeAllocation(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            LocalDateTime now
    ) {
        return finalizeAllocation(settlementId, settlementTotalAmount, participants,
                items, selections, now, 0);
    }

    public Finalization finalizeAllocation(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            LocalDateTime now,
            long receiptDiscount
    ) {
        AllocationEvaluation evaluation = evaluateAllocation(
                settlementId,
                settlementTotalAmount,
                participants,
                items,
                selections,
                receiptDiscount
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
        return findInvalidItemIds(settlementId, settlementTotalAmount,
                participants, items, selections, 0);
    }

    public List<UUID> findInvalidItemIds(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            long receiptDiscount
    ) {
        return evaluateAllocation(
                settlementId,
                settlementTotalAmount,
                participants,
                items,
                selections,
                receiptDiscount
        ).invalidItemIds();
    }

    private AllocationEvaluation evaluateAllocation(
            UUID settlementId,
            long settlementTotalAmount,
            List<SettlementParticipant> participants,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            long receiptDiscount
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
        // 내림 때문에 합이 총액보다 적을 수 있다. 그 차액은 생성자가 부담한다.
        // 다만 선택 하나당 최대 1원까지만 줄어들 수 있어, 그보다 크게 어긋나면 배분이 잘못된 것이다.
        if (receiptDiscount < 0 || receiptDiscount > shareTotal) {
            return AllocationEvaluation.invalid(items.stream().map(SettlementItem::id).toList());
        }
        Map<UUID, Long> discountShares = distributeDiscount(
                amountByParticipant, receiptDiscount
        );
        discountShares.forEach((participantId, share) -> amountByParticipant.computeIfPresent(
                participantId, (id, amount) -> Math.subtractExact(amount, share)
        ));
        long shortfall = Math.subtractExact(
                settlementTotalAmount, Math.subtractExact(shareTotal, receiptDiscount)
        );
        if (shortfall < 0L || shortfall > selections.size() || !hasPositiveShare) {
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
