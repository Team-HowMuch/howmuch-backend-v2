package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Experimental exact-pair preprocessing followed by the existing 12-person bitmask DP. */
public final class PairReducedDpNettingCalculator implements SettlementNettingCalculator {
    public static final NettingAlgorithmKey KEY =
            new NettingAlgorithmKey("PAIR_REDUCED_DP_EXPERIMENTAL", 1);
    private static final int MAX_NON_ZERO_PARTICIPANTS =
            BitmaskDpSettlementNettingCalculator.MAX_NON_ZERO_PARTICIPANTS;

    public record ReductionStats(
            int originalNonZeroBalances, int exactPairCount, int residualNonZeroBalances) {}

    public record ReductionAnalysis(
            int originalNonZeroBalances,
            List<NettingPayment> exactPairs,
            Map<Long, Long> residualBalances) {
        public ReductionAnalysis {
            exactPairs = List.copyOf(exactPairs);
            residualBalances = Map.copyOf(residualBalances);
        }

        public int residualNonZeroBalances() {
            return residualBalances.size();
        }
    }

    private record Reduction(List<NettingPayment> payments, Map<Long, Long> balances) {}

    /** Shared membership and expected balances; candidate sums remain independent. */
    private record ValidationFacts(long[] participants, long[] expectedBalances) {
        static ValidationFacts from(NettingCalculationContext context) {
            var participants = new HashSet<Long>();
            context.debts()
                    .forEach(
                            debt -> {
                                participants.add(debt.payerId());
                                participants.add(debt.receiverId());
                            });
            long[] ids = participants.stream().mapToLong(Long::longValue).sorted().toArray();
            long[] expected = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                expected[i] = context.balances().getOrDefault(ids[i], 0L);
            }
            return new ValidationFacts(ids, expected);
        }
    }

    private final GreedySettlementNettingCalculator greedy =
            new GreedySettlementNettingCalculator();
    private final BitmaskDpSettlementNettingCalculator dp =
            new BitmaskDpSettlementNettingCalculator();

    @Override
    public NettingAlgorithmKey key() {
        return KEY;
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public boolean supports(NettingCalculationContext context) {
        return context != null;
    }

    /**
     * Runs the experiment. Unsupported residual sizes return the validated lower-transfer Greedy or
     * Baseline.
     */
    @Override
    public List<NettingPayment> calculate(NettingCalculationContext context) {
        return calculatePrepared(context, null, null);
    }

    /**
     * Package-local preparation is derived from the same immutable context by the production
     * wrapper.
     */
    List<NettingPayment> calculatePrepared(
            NettingCalculationContext context,
            List<NettingPayment> precomputedGreedy,
            ReductionAnalysis preparedReduction) {
        if (context == null) throw new NullPointerException("Calculation context is required");
        validateInput(context);
        var validation = ValidationFacts.from(context);
        var greedyCandidate =
                List.copyOf(
                        precomputedGreedy == null ? greedy.calculate(context) : precomputedGreedy);
        if (!isValidCandidate(validation, greedyCandidate)) {
            throw new IllegalArgumentException("Greedy result does not preserve netting balances");
        }
        var fallback = bestFallback(context, greedyCandidate, validation);

        int lowerBound =
                Math.toIntExact(
                        Math.max(
                                context.balances().values().stream()
                                        .filter(value -> value < 0)
                                        .count(),
                                context.balances().values().stream()
                                        .filter(value -> value > 0)
                                        .count()));
        if (greedyCandidate.size() == lowerBound) return fallback;

        // Isolating a +x/-x pair cannot decrease the maximum number of zero-sum groups.
        var reduced =
                preparedReduction == null
                        ? reduce(context.balances())
                        : new Reduction(
                                preparedReduction.exactPairs(),
                                preparedReduction.residualBalances());
        if (reduced.balances().size() > MAX_NON_ZERO_PARTICIPANTS) return fallback;

        var residualContext =
                new NettingCalculationContext(List.of(), List.of(), reduced.balances());
        var merged = new ArrayList<NettingPayment>(reduced.payments());
        // DP errors propagate. Returning the already-built exact pairs alone would lose balances.
        merged.addAll(dp.calculate(residualContext));
        merged.sort(
                Comparator.comparingLong(NettingPayment::payerId)
                        .thenComparingLong(NettingPayment::receiverId));
        var candidate = List.copyOf(merged);
        if (!isValidCandidate(validation, candidate)) {
            throw new IllegalStateException(
                    "Pair reduction result does not preserve netting balances");
        }
        return candidate.size() < fallback.size() ? candidate : fallback;
    }

    /** Diagnostics for figures; calculation timings never include this setup-only inspection. */
    public ReductionStats reductionStats(NettingCalculationContext context) {
        var analysis = analyzeReduction(context);
        return new ReductionStats(
                analysis.originalNonZeroBalances(),
                analysis.exactPairs().size(),
                analysis.residualNonZeroBalances());
    }

    public ReductionAnalysis analyzeReduction(NettingCalculationContext context) {
        int original =
                (int) context.balances().values().stream().filter(value -> value != 0).count();
        var reduced = reduce(context.balances());
        return new ReductionAnalysis(original, reduced.payments(), reduced.balances());
    }

    private Reduction reduce(Map<Long, Long> input) {
        var waiting = new HashMap<Long, ArrayDeque<Map.Entry<Long, Long>>>();
        var pairs = new ArrayList<NettingPayment>();
        BigInteger total = BigInteger.ZERO;
        for (var entry : input.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            long id = entry.getKey();
            long amount = entry.getValue();
            if (id <= 0) throw new IllegalArgumentException("Invalid participant id");
            total = total.add(BigInteger.valueOf(amount));
            if (amount == 0) continue;
            long oppositeAmount = Math.negateExact(amount);
            var opposite = waiting.get(oppositeAmount);
            if (opposite != null && !opposite.isEmpty()) {
                var other = opposite.removeFirst();
                long payer = amount < 0 ? id : other.getKey();
                long receiver = amount > 0 ? id : other.getKey();
                pairs.add(
                        new NettingPayment(payer, receiver, amount > 0 ? amount : oppositeAmount));
            } else {
                // Ascending IDs and FIFO preserve the original k-th payer / k-th receiver match.
                waiting.computeIfAbsent(amount, ignored -> new ArrayDeque<>()).addLast(entry);
            }
        }
        if (total.signum() != 0)
            throw new IllegalArgumentException("Netting balances must sum to zero");

        var residual = new HashMap<Long, Long>();
        for (var unmatched : waiting.values()) {
            for (var entry : unmatched) {
                residual.put(entry.getKey(), entry.getValue());
            }
        }
        pairs.sort(
                Comparator.comparingLong(NettingPayment::payerId)
                        .thenComparingLong(NettingPayment::receiverId));
        return new Reduction(List.copyOf(pairs), Map.copyOf(residual));
    }

    private void validateInput(NettingCalculationContext context) {
        BigInteger total = BigInteger.ZERO;
        for (var entry : context.balances().entrySet()) {
            if (entry.getKey() <= 0) throw new IllegalArgumentException("Invalid participant id");
            if (entry.getValue() == Long.MIN_VALUE) {
                throw new ArithmeticException("Balance magnitude exceeds long range");
            }
            total = total.add(BigInteger.valueOf(entry.getValue()));
        }
        if (total.signum() != 0)
            throw new IllegalArgumentException("Netting balances must sum to zero");
        if (!NettingBalanceSupport.balances(context.baseline()).equals(context.balances())) {
            throw new IllegalArgumentException("Baseline does not match netting balances");
        }
    }

    /** Chooses the validated lower-transfer Greedy or Baseline candidate; Baseline wins ties. */
    private List<NettingPayment> bestFallback(
            NettingCalculationContext context,
            List<NettingPayment> greedyCandidate,
            ValidationFacts validation) {
        var baseline = context.baseline();
        if (!isValidCandidate(validation, baseline)) {
            throw new IllegalArgumentException("Baseline does not preserve netting balances");
        }
        // Match Engine's rule: use baseline on ties; otherwise keep the lower-transfer validated
        // plan.
        return baseline.size() <= greedyCandidate.size() ? baseline : greedyCandidate;
    }

    private boolean isValidCandidate(ValidationFacts facts, List<NettingPayment> candidate) {
        try {
            var seen = new HashSet<Long>();
            long[] actual = new long[facts.participants().length];
            for (var payment : candidate) {
                int payer = Arrays.binarySearch(facts.participants(), payment.payerId());
                int receiver = Arrays.binarySearch(facts.participants(), payment.receiverId());
                // Indices, rather than user IDs, fit in 32 bits without truncating a long ID.
                long pair = ((long) payer << 32) | (receiver & 0xffffffffL);
                if (payer < 0
                        || receiver < 0
                        || payment.payerId() == payment.receiverId()
                        || payment.amount() <= 0
                        || !seen.add(pair)) return false;
                actual[payer] = Math.addExact(actual[payer], Math.negateExact(payment.amount()));
                actual[receiver] = Math.addExact(actual[receiver], payment.amount());
            }
            return Arrays.equals(actual, facts.expectedBalances());
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) {
            return false;
        }
    }
}
