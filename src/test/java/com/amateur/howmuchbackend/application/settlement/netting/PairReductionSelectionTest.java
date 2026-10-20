package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCalculatorSelectionPolicy;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingCalculationEngine;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingCalculatorSelector;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class PairReductionSelectionTest {
    private NettingDebt debt(long payer, long receiver, long amount) {
        return new NettingDebt(UUID.randomUUID(), UUID.randomUUID(), payer, receiver, amount, 0);
    }

    private List<NettingDebt> counterexample(long offset, long scale) {
        return List.of(
                debt(offset + 4, offset + 1, 60 * scale),
                debt(offset + 5, offset + 1, 20 * scale),
                debt(offset + 5, offset + 2, 30 * scale),
                debt(offset + 6, offset + 2, 10 * scale),
                debt(offset + 6, offset + 3, 20 * scale));
    }

    private List<NettingDebt> pairInput() {
        var debts = new ArrayList<>(counterexample(0, 1000));
        debts.add(debt(7, 8, 7777));
        return debts;
    }

    private NettingCalculatorSelector selector(boolean enabled) {
        return new NettingCalculatorSelector(
                List.of(
                        new BaselineSettlementNettingCalculator(),
                                new GreedySettlementNettingCalculator(),
                        new BitmaskDpSettlementNettingCalculator(),
                                new PairReductionSettlementNettingCalculator()),
                new NettingCalculatorSelectionPolicy(),
                new NettingCalculatorFeatures(enabled));
    }

    private NettingCalculationEngine engine(boolean enabled) {
        return new NettingCalculationEngine(selector(enabled), new NettingResultValidator());
    }

    @Test
    void selectsProductionPairWithPolicyThreeAndPreservesBalancesDeterministically() {
        var debts = pairInput();
        var result = engine(true).calculate(debts);
        assertThat(result.selection())
                .isEqualTo(
                        new NettingCalculatorSelection(
                                PairReductionSettlementNettingCalculator.KEY, 3));
        assertThat(result.calculation().algorithm()).isEqualTo("PAIR_REDUCED_DP_NET_V1");
        assertThat(result.calculation().payments()).hasSize(5);
        new NettingResultValidator()
                .validate(NettingCalculationContext.from(debts), result.calculation());
        Collections.shuffle(debts, new Random(42));
        assertThat(engine(true).calculate(debts)).isEqualTo(result);
    }

    @Test
    void noExactPairsKeepsOrdinaryDp() {
        var result = engine(true).calculate(counterexample(0, 1000));
        assertThat(result.selection().algorithm())
                .isEqualTo(BitmaskDpSettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments()).hasSize(4);
    }

    @Test
    void residualTwelveIsEligibleEvenWhenOriginalInputExceedsDpLimit() {
        var debts = new ArrayList<>(pairInput());
        debts.addAll(counterexample(10, 10000));
        var context = NettingCalculationContext.from(debts);
        assertThat(
                        new PairReducedDpNettingCalculator()
                                .analyzeReduction(context)
                                .residualNonZeroBalances())
                .isEqualTo(12);
        assertThat(new BitmaskDpSettlementNettingCalculator().supports(context)).isFalse();
        var result = engine(true).calculate(debts);
        assertThat(result.selection().algorithm())
                .isEqualTo(PairReductionSettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments()).hasSize(9);
        new NettingResultValidator().validate(context, result.calculation());
    }

    @Test
    void residualOverTwelveKeepsLegacyHybridAndPinnedUnsupportedInputIsStale() {
        long[] creditors = {20, 18, 16, 14, 10, 8, 4};
        long[] debtors = {23, 19, 17, 13, 11, 7};
        var debts = new ArrayList<NettingDebt>();
        int c = 0, d = 0;
        while (c < creditors.length && d < debtors.length) {
            long amount = Math.min(creditors[c], debtors[d]);
            debts.add(debt(101 + d, 1 + c, amount));
            creditors[c] -= amount;
            debtors[d] -= amount;
            if (creditors[c] == 0) c++;
            if (debtors[d] == 0) d++;
        }
        debts.add(debt(201, 202, 7777));
        var context = NettingCalculationContext.from(debts);
        assertThat(
                        new PairReducedDpNettingCalculator()
                                .analyzeReduction(context)
                                .residualNonZeroBalances())
                .isEqualTo(13);
        assertThat(selector(true).select(context).algorithm())
                .isEqualTo(GreedySettlementNettingCalculator.KEY);
        assertThatThrownBy(
                        () ->
                                engine(true)
                                        .recalculate(
                                                debts,
                                                new NettingCalculatorSelection(
                                                        PairReductionSettlementNettingCalculator
                                                                .KEY,
                                                        3)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_INPUT_CHANGED));
    }

    @Test
    void flagDisablesOnlyNewSelectionAndKeepsPinnedPairExecutable() {
        var debts = pairInput();
        var pinned = engine(true).calculate(debts);
        var disabled = engine(false);
        assertThat(disabled.calculate(debts).selection())
                .isEqualTo(
                        new NettingCalculatorSelection(
                                BitmaskDpSettlementNettingCalculator.KEY, 2));
        assertThat(disabled.recalculate(debts, pinned.selection())).isEqualTo(pinned);
    }

    @Test
    void legacyDpAndGreedyVersionsAreNeverReselectedAsPair() {
        var debts = pairInput();
        debts.add(debt(1, 2, 100));
        debts.add(debt(2, 3, 100));
        debts.add(debt(3, 1, 100));
        assertThat(engine(true).calculate(debts).selection().algorithm())
                .isEqualTo(PairReductionSettlementNettingCalculator.KEY);
        for (var pinned :
                List.of(
                        new NettingCalculatorSelection(BitmaskDpSettlementNettingCalculator.KEY, 2),
                        new NettingCalculatorSelection(GreedySettlementNettingCalculator.KEY, 1))) {
            var result = engine(true).recalculate(debts, pinned);
            assertThat(result.selection()).isEqualTo(pinned);
            assertThat(result.calculation().algorithm()).isEqualTo(pinned.algorithm().identifier());
        }
    }

    @Test
    void equalCountRetainsBaselineAndSelectedPairIdentity() {
        var optimal =
                new BitmaskDpSettlementNettingCalculator()
                        .calculate(NettingCalculationContext.from(pairInput()));
        var debts =
                optimal.stream().map(p -> debt(p.payerId(), p.receiverId(), p.amount())).toList();
        var result = engine(true).calculate(debts);
        assertThat(result.selection().algorithm())
                .isEqualTo(PairReductionSettlementNettingCalculator.KEY);
        assertThat(result.calculation().algorithm())
                .isEqualTo(BaselineSettlementNettingCalculator.KEY.identifier());
        assertThat(result.calculation().payments())
                .containsExactlyElementsOf(NettingCalculationContext.from(debts).baseline());
    }

    @Test
    void lowerBoundSkipsPairPreparationEntirely() {
        var pair =
                new SettlementNettingCalculator() {
                    public NettingAlgorithmKey key() {
                        return PairReductionSettlementNettingCalculator.KEY;
                    }

                    public int priority() {
                        return 300;
                    }

                    public boolean supports(NettingCalculationContext c) {
                        throw new AssertionError("Pair support must be skipped");
                    }

                    public List<NettingPayment> calculate(NettingCalculationContext c) {
                        throw new AssertionError("Pair calculation must be skipped");
                    }
                };
        var selected =
                new NettingCalculatorSelector(
                        List.of(
                                pair,
                                new GreedySettlementNettingCalculator(),
                                new BitmaskDpSettlementNettingCalculator()),
                        new NettingCalculatorSelectionPolicy());
        var result =
                new NettingCalculationEngine(selected, new NettingResultValidator())
                        .calculate(List.of(debt(1, 2, 100)));
        assertThat(result.selection().algorithm()).isEqualTo(GreedySettlementNettingCalculator.KEY);
    }

    @Test
    void passesOneGreedyCandidateAndReusesOnePairPreparationForExecution() {
        var greedyCalls = new AtomicInteger();
        var pairPreparations = new AtomicInteger();
        var greedy =
                new SettlementNettingCalculator() {
                    public NettingAlgorithmKey key() {
                        return GreedySettlementNettingCalculator.KEY;
                    }

                    public int priority() {
                        return 100;
                    }

                    public boolean supports(NettingCalculationContext c) {
                        return true;
                    }

                    public List<NettingPayment> calculate(NettingCalculationContext c) {
                        greedyCalls.incrementAndGet();
                        return new GreedySettlementNettingCalculator().calculate(c);
                    }
                };
        var pair =
                new SettlementNettingCalculator() {
                    private final PairReductionSettlementNettingCalculator delegate =
                            new PairReductionSettlementNettingCalculator();

                    public NettingAlgorithmKey key() {
                        return delegate.key();
                    }

                    public int priority() {
                        return delegate.priority();
                    }

                    public boolean supports(NettingCalculationContext c) {
                        throw new AssertionError("Must use preparation");
                    }

                    public List<NettingPayment> calculate(NettingCalculationContext c) {
                        throw new AssertionError("Must use prepared calculation");
                    }

                    public Optional<PreparedNettingCalculator> prepare(
                            NettingCalculationContext c, List<NettingPayment> g) {
                        pairPreparations.incrementAndGet();
                        assertThat(g).isNotNull();
                        return delegate.prepare(c, g);
                    }
                };
        var selected =
                new NettingCalculatorSelector(
                        List.of(greedy, pair), new NettingCalculatorSelectionPolicy());
        var result =
                new NettingCalculationEngine(selected, new NettingResultValidator())
                        .calculate(pairInput());
        assertThat(result.calculation().payments()).hasSize(5);
        assertThat(greedyCalls).hasValue(1);
        assertThat(pairPreparations).hasValue(1);
    }

    @Test
    void productionPairRejectsInvalidBaselineAndLongMinBalance() {
        var valid = NettingCalculationContext.from(pairInput());
        var invalidBaseline = new ArrayList<>(valid.baseline());
        invalidBaseline.add(valid.baseline().getFirst());
        var pair = new PairReductionSettlementNettingCalculator();
        assertThatThrownBy(
                        () ->
                                pair.calculate(
                                        new NettingCalculationContext(
                                                valid.debts(), invalidBaseline, valid.balances())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                pair.prepare(
                                        new NettingCalculationContext(
                                                List.of(),
                                                List.of(),
                                                Map.of(
                                                        1L,
                                                        Long.MIN_VALUE,
                                                        2L,
                                                        Long.MAX_VALUE,
                                                        3L,
                                                        1L)),
                                        null))
                .isInstanceOf(ArithmeticException.class);
    }
}
