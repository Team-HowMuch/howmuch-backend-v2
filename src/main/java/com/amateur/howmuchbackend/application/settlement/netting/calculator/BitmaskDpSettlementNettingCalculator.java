package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Finds a minimum-transfer settlement for small sets of non-zero participant balances. */
@Component
public class BitmaskDpSettlementNettingCalculator implements SettlementNettingCalculator {
    public static final int MAX_NON_ZERO_PARTICIPANTS = 12;
    public static final NettingAlgorithmKey KEY = new NettingAlgorithmKey("BITMASK_DP_NET", 1);

    private record Balance(long userId, long amount) {}

    private record Reconstruction(List<List<Integer>> groups, List<Integer> unmatched) {}

    @Override
    public NettingAlgorithmKey key() {
        return KEY;
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public boolean supports(NettingCalculationContext context) {
        return context.balances().values().stream().filter(amount -> amount != 0).count()
                <= MAX_NON_ZERO_PARTICIPANTS;
    }

    @Override
    public List<NettingPayment> calculate(NettingCalculationContext context) {
        Objects.requireNonNull(context, "Calculation context is required");
        var balances =
                context.balances().entrySet().stream()
                        .filter(entry -> entry.getValue() != 0)
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new Balance(entry.getKey(), entry.getValue()))
                        .toList();
        if (balances.size() > MAX_NON_ZERO_PARTICIPANTS) {
            throw new IllegalArgumentException("Too many non-zero balances for bitmask DP");
        }

        BigInteger total = BigInteger.ZERO;
        for (var balance : balances) {
            if (balance.userId() <= 0) {
                throw new IllegalArgumentException("Invalid participant id");
            }
            total = total.add(BigInteger.valueOf(balance.amount()));
            if (balance.amount() == Long.MIN_VALUE) {
                throw new ArithmeticException("Balance magnitude exceeds long range");
            }
        }
        if (total.signum() != 0) {
            throw new IllegalArgumentException("Netting balances must sum to zero");
        }
        if (balances.isEmpty()) return List.of();

        int stateCount = 1 << balances.size();
        long[] subsetSums = new long[stateCount];
        int[] maxZeroPrefixBoundaries = new int[stateCount];
        int[] removedIndex = new int[stateCount];
        removedIndex[0] = -1;

        for (int mask = 1; mask < stateCount; mask++) {
            int bit = Integer.numberOfTrailingZeros(mask);
            int previous = mask & (mask - 1);
            subsetSums[mask] = Math.addExact(subsetSums[previous], balances.get(bit).amount());

            // maxZeroPrefixBoundaries[mask] is the maximum count of zero-sum prefix boundaries
            // across permutations of the users in mask. Removing the final user of a permutation
            // leaves one of the smaller masks; the full mask has total zero, so these boundaries
            // delimit a maximum zero-sum partition. This avoids enumerating every submask.
            int addedBoundary = subsetSums[mask] == 0 ? 1 : 0;
            int best = -1;
            int bestRemoved = -1;
            for (int remaining = mask; remaining != 0; remaining &= remaining - 1) {
                int candidateIndex = Integer.numberOfTrailingZeros(remaining);
                int candidate =
                        Math.addExact(
                                maxZeroPrefixBoundaries[mask & ~(1 << candidateIndex)],
                                addedBoundary);
                if (candidate > best) {
                    best = candidate;
                    bestRemoved = candidateIndex;
                }
            }
            maxZeroPrefixBoundaries[mask] = best;
            removedIndex[mask] = bestRemoved;
        }

        int fullMask = stateCount - 1;
        if (subsetSums[fullMask] != 0) {
            throw new IllegalArgumentException("Netting balances must sum to zero");
        }

        var reconstruction = reconstruct(fullMask, subsetSums, removedIndex);
        if (!reconstruction.unmatched().isEmpty()
                || reconstruction.groups().size() != maxZeroPrefixBoundaries[fullMask]) {
            throw new IllegalArgumentException("Could not reconstruct zero-sum groups");
        }

        var groups =
                reconstruction.groups().stream()
                        .map(
                                group ->
                                        group.stream()
                                                .sorted(
                                                        Comparator.comparingLong(
                                                                index ->
                                                                        balances.get(index)
                                                                                .userId()))
                                                .toList())
                        .sorted(
                                Comparator.comparingLong(
                                        group -> balances.get(group.getFirst()).userId()))
                        .toList();
        List<NettingPayment> payments = new ArrayList<>();
        for (var group : groups) addGroupPayments(group, balances, payments);
        payments.sort(
                Comparator.comparingLong(NettingPayment::payerId)
                        .thenComparingLong(NettingPayment::receiverId));

        int expectedTransfers = balances.size() - maxZeroPrefixBoundaries[fullMask];
        if (payments.size() != expectedTransfers) {
            throw new IllegalArgumentException("Reconstructed transfer count is inconsistent");
        }
        return List.copyOf(payments);
    }

    private Reconstruction reconstruct(int mask, long[] subsetSums, int[] removedIndex) {
        if (mask == 0) return new Reconstruction(List.of(), List.of());

        int index = removedIndex[mask];
        if (index < 0 || (mask & (1 << index)) == 0) {
            throw new IllegalArgumentException("Invalid DP reconstruction state");
        }
        var previous = reconstruct(mask & ~(1 << index), subsetSums, removedIndex);
        if (subsetSums[mask] == 0) {
            if (previous.unmatched().isEmpty()) {
                throw new IllegalArgumentException("Zero-sum group reconstruction is empty");
            }
            var group = new ArrayList<>(previous.unmatched());
            group.add(index);
            var groups = new ArrayList<>(previous.groups());
            groups.add(List.copyOf(group));
            return new Reconstruction(List.copyOf(groups), List.of());
        }

        var unmatched = new ArrayList<>(previous.unmatched());
        unmatched.add(index);
        return new Reconstruction(previous.groups(), List.copyOf(unmatched));
    }

    private void addGroupPayments(
            List<Integer> group, List<Balance> balances, List<NettingPayment> payments) {
        var debtors = new ArrayList<Balance>();
        var creditors = new ArrayList<Balance>();
        long groupSum = 0;
        for (int index : group) {
            var balance = balances.get(index);
            groupSum = Math.addExact(groupSum, balance.amount());
            if (balance.amount() < 0) {
                debtors.add(new Balance(balance.userId(), Math.negateExact(balance.amount())));
            } else {
                creditors.add(balance);
            }
        }
        if (groupSum != 0 || debtors.isEmpty() || creditors.isEmpty()) {
            throw new IllegalArgumentException("Reconstructed group is not zero-sum");
        }

        int debtorIndex = 0;
        int creditorIndex = 0;
        long debtorRemaining = debtors.getFirst().amount();
        long creditorRemaining = creditors.getFirst().amount();
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            long amount = Math.min(debtorRemaining, creditorRemaining);
            payments.add(
                    new NettingPayment(
                            debtors.get(debtorIndex).userId(),
                            creditors.get(creditorIndex).userId(),
                            amount));
            debtorRemaining -= amount;
            creditorRemaining -= amount;
            if (debtorRemaining == 0 && ++debtorIndex < debtors.size()) {
                debtorRemaining = debtors.get(debtorIndex).amount();
            }
            if (creditorRemaining == 0 && ++creditorIndex < creditors.size()) {
                creditorRemaining = creditors.get(creditorIndex).amount();
            }
        }
        if (debtorIndex != debtors.size() || creditorIndex != creditors.size()) {
            throw new IllegalArgumentException("Could not balance zero-sum group");
        }
    }
}
