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
import java.util.function.Function;
import java.util.function.Predicate;

class NettingCalculationEngineTest {
    private record Calculator(
            NettingAlgorithmKey key,
            int priority,
            Predicate<NettingCalculationContext> support,
            Function<NettingCalculationContext, List<NettingPayment>> operation)
            implements SettlementNettingCalculator {
        public boolean supports(NettingCalculationContext context) {
            return support.test(context);
        }

        public List<NettingPayment> calculate(NettingCalculationContext context) {
            return operation.apply(context);
        }
    }

    private Calculator calculator(
            String name,
            int version,
            int priority,
            Predicate<NettingCalculationContext> support,
            Function<NettingCalculationContext, List<NettingPayment>> operation) {
        return new Calculator(new NettingAlgorithmKey(name, version), priority, support, operation);
    }

    private NettingDebt debt(long payer, long receiver, long amount) {
        return new NettingDebt(UUID.randomUUID(), UUID.randomUUID(), payer, receiver, amount, 0);
    }

    private List<NettingDebt> opposite() {
        return List.of(debt(1, 2, 15000), debt(2, 1, 10000));
    }

    private List<NettingDebt> greedyCounterexample() {
        return List.of(
                debt(4, 1, 60_000),
                debt(5, 1, 20_000),
                debt(5, 2, 30_000),
                debt(6, 2, 10_000),
                debt(6, 3, 20_000));
    }

    private List<NettingDebt> thirteenParticipantDebts() {
        long[] creditorBalances = {20, 18, 16, 14, 10, 8, 4};
        long[] debtorBalances = {23, 19, 17, 13, 11, 7};
        List<NettingDebt> debts = new ArrayList<>();
        int creditorIndex = 0;
        int debtorIndex = 0;
        long creditorRemaining = creditorBalances[0];
        long debtorRemaining = debtorBalances[0];
        while (creditorIndex < creditorBalances.length && debtorIndex < debtorBalances.length) {
            long amount = Math.min(creditorRemaining, debtorRemaining);
            debts.add(debt(101 + debtorIndex, 1 + creditorIndex, amount));
            creditorRemaining -= amount;
            debtorRemaining -= amount;
            if (creditorRemaining == 0 && ++creditorIndex < creditorBalances.length) {
                creditorRemaining = creditorBalances[creditorIndex];
            }
            if (debtorRemaining == 0 && ++debtorIndex < debtorBalances.length) {
                debtorRemaining = debtorBalances[debtorIndex];
            }
        }
        return debts;
    }

    private NettingCalculatorSelector selector(SettlementNettingCalculator... calculators) {
        return new NettingCalculatorSelector(
                List.of(calculators), new NettingCalculatorSelectionPolicy());
    }

    private NettingCalculationEngine engine(SettlementNettingCalculator... calculators) {
        return new NettingCalculationEngine(selector(calculators), new NettingResultValidator());
    }

    private void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, NettingStatusCode code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(code));
    }

    @Test
    void selectsSupportedHigherPriorityAndExecutesOnlySelectedCalculator() {
        var unsupported =
                calculator(
                        "UNSUPPORTED",
                        1,
                        1000,
                        c -> c.debtCount() > 100,
                        c -> {
                            throw new AssertionError("Unsupported calculator executed");
                        });
        var lower =
                calculator(
                        "LOWER",
                        1,
                        1,
                        c -> true,
                        c -> {
                            throw new AssertionError("Unselected calculator executed");
                        });
        var result =
                engine(unsupported, lower, new GreedySettlementNettingCalculator())
                        .calculate(opposite());
        assertThat(result.selection().algorithm()).isEqualTo(GreedySettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments()).containsExactly(new NettingPayment(1, 2, 5000));
    }

    @Test
    void usesBitmaskDpWhenGreedyExceedsTheTheoreticalLowerBound() {
        var result =
                engine(
                                new GreedySettlementNettingCalculator(),
                                new BitmaskDpSettlementNettingCalculator())
                        .calculate(greedyCounterexample());

        assertThat(result.selection().algorithm())
                .isEqualTo(BitmaskDpSettlementNettingCalculator.KEY);
        assertThat(result.selection().policyVersion()).isEqualTo(3);
        assertThat(result.calculation().payments()).hasSize(4);
    }

    @Test
    void duplicateOriginalDebtRowsStillUseTheAggregatedBalanceForDp() {
        var debts =
                List.of(
                        debt(4, 1, 30_000),
                        debt(4, 1, 30_000),
                        debt(5, 1, 20_000),
                        debt(5, 2, 30_000),
                        debt(6, 2, 10_000),
                        debt(6, 3, 20_000));

        var result =
                engine(
                                new GreedySettlementNettingCalculator(),
                                new BitmaskDpSettlementNettingCalculator())
                        .calculate(debts);

        assertThat(result.selection().algorithm())
                .isEqualTo(BitmaskDpSettlementNettingCalculator.KEY);
        assertThat(result.calculation().originalTransferCount()).isEqualTo(5);
        assertThat(result.calculation().payments()).hasSize(4);
    }

    @Test
    void preservesExistingHigherPriorityCalculatorsWhenDpIsEligible() {
        var higherPriority =
                calculator("EXACT_CUSTOM", 1, 300, c -> true, NettingCalculationContext::baseline);
        var dp =
                calculator(
                        BitmaskDpSettlementNettingCalculator.KEY.name(),
                        BitmaskDpSettlementNettingCalculator.KEY.version(),
                        200,
                        c -> true,
                        c -> {
                            throw new AssertionError("Higher-priority calculator should win");
                        });

        var result =
                engine(new GreedySettlementNettingCalculator(), dp, higherPriority)
                        .calculate(greedyCounterexample());

        assertThat(result.selection().algorithm()).isEqualTo(higherPriority.key());
        assertThat(result.calculation().algorithm()).isEqualTo("AGGREGATED_BASELINE_V1");
    }

    @Test
    void skipsDpAtGreedyLowerBoundAndReusesTheGreedyCandidate() {
        var greedyCalls = new AtomicInteger();
        var greedy =
                calculator(
                        "GREEDY_NET_BALANCE",
                        1,
                        100,
                        c -> true,
                        c -> {
                            greedyCalls.incrementAndGet();
                            return new GreedySettlementNettingCalculator().calculate(c);
                        });
        var dp =
                calculator(
                        BitmaskDpSettlementNettingCalculator.KEY.name(),
                        BitmaskDpSettlementNettingCalculator.KEY.version(),
                        200,
                        c -> true,
                        c -> {
                            throw new AssertionError("DP should be skipped at the lower bound");
                        });

        var result = engine(greedy, dp).calculate(opposite());

        assertThat(result.selection().algorithm()).isEqualTo(greedy.key());
        assertThat(result.calculation().payments()).containsExactly(new NettingPayment(1, 2, 5000));
        assertThat(greedyCalls).hasValue(1);
    }

    @Test
    void pinnedDpIsReusedWhenCurrentGreedyWouldReachItsLowerBound() {
        var dpCalls = new AtomicInteger();
        var dpCalculator = new BitmaskDpSettlementNettingCalculator();
        var countedDp =
                calculator(
                        BitmaskDpSettlementNettingCalculator.KEY.name(),
                        BitmaskDpSettlementNettingCalculator.KEY.version(),
                        dpCalculator.priority(),
                        dpCalculator::supports,
                        c -> {
                            dpCalls.incrementAndGet();
                            return dpCalculator.calculate(c);
                        });
        var engine = engine(new GreedySettlementNettingCalculator(), countedDp);
        var initial = engine.calculate(greedyCounterexample());
        assertThat(initial.selection().algorithm())
                .isEqualTo(BitmaskDpSettlementNettingCalculator.KEY);

        var recalculated = engine.recalculate(opposite(), initial.selection());

        assertThat(recalculated.selection()).isEqualTo(initial.selection());
        assertThat(recalculated.calculation().algorithm())
                .isEqualTo(BitmaskDpSettlementNettingCalculator.KEY.identifier());
        assertThat(recalculated.calculation().payments())
                .containsExactly(new NettingPayment(1, 2, 5000));
        assertThat(dpCalls).hasValue(2);
    }

    @Test
    void zeroBalanceParticipantsRemainInTheContextWhileDpOptimizesNonZeroBalancesOnly() {
        var debts = new ArrayList<>(greedyCounterexample());
        debts.add(debt(90, 91, 100));
        debts.add(debt(91, 90, 100));
        var context = NettingCalculationContext.from(debts);

        assertThat(context.participantCount()).isEqualTo(8);
        assertThat(context.balances()).hasSize(6);
        assertThat(context.balances()).doesNotContainKeys(90L, 91L);
        assertThat(new BitmaskDpSettlementNettingCalculator().supports(context)).isTrue();
        assertThat(
                        engine(
                                        new GreedySettlementNettingCalculator(),
                                        new BitmaskDpSettlementNettingCalculator())
                                .calculate(debts)
                                .calculation()
                                .payments())
                .hasSize(4);
    }

    @Test
    void selectsGreedyWhenThirteenNonZeroParticipantsExceedDpSupport() {
        var debts = thirteenParticipantDebts();
        var context = NettingCalculationContext.from(debts);
        var greedy = new GreedySettlementNettingCalculator().calculate(context);

        assertThat(context.balances()).hasSize(13);
        assertThat(greedy).hasSizeGreaterThan(7);
        var result =
                engine(
                                new GreedySettlementNettingCalculator(),
                                new BitmaskDpSettlementNettingCalculator())
                        .calculate(debts);

        assertThat(result.selection().algorithm()).isEqualTo(GreedySettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments())
                .containsExactlyElementsOf(
                        greedy.stream()
                                .sorted(
                                        Comparator.comparingLong(NettingPayment::payerId)
                                                .thenComparingLong(NettingPayment::receiverId))
                                .toList());
    }

    @Test
    void tieBreakUsesNameThenDescendingVersionRegardlessOfRegistrationOrder() {
        var a1 =
                calculator(
                        "EXACT_A", 1, 200, c -> true, c -> List.of(new NettingPayment(1, 2, 5000)));
        var a2 = calculator("EXACT_A", 2, 200, c -> true, a1::calculate);
        var b = calculator("EXACT_B", 3, 200, c -> true, a1::calculate);
        assertThat(engine(a1, b, a2).calculate(opposite()).selection().algorithm())
                .isEqualTo(a2.key());
        assertThat(engine(a2, b, a1).calculate(opposite()).selection().algorithm())
                .isEqualTo(a2.key());
    }

    @Test
    void duplicateKeyIsRejectedAndDifferentVersionsAreAllowed() {
        var first = calculator("EXACT", 1, 1, c -> true, c -> c.baseline());
        var duplicate = calculator("EXACT", 1, 200, c -> true, c -> c.baseline());
        assertThatThrownBy(() -> selector(first, duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate netting calculator");
        assertThatCode(
                        () ->
                                selector(
                                        first,
                                        calculator("EXACT", 2, 200, c -> true, c -> c.baseline())))
                .doesNotThrowAnyException();
    }

    @Test
    void noSupportedCalculatorReturnsExplicitUnavailableCode() {
        assertCode(
                () ->
                        engine(calculator("NONE", 1, 100, c -> false, c -> c.baseline()))
                                .calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATOR_UNAVAILABLE);
        assertCode(
                () -> engine().calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATOR_UNAVAILABLE);
    }

    @Test
    void baselineRemainsAvailableWhenOtherCalculatorDoesNotSupportInput() {
        var result =
                engine(
                                new BaselineSettlementNettingCalculator(),
                                calculator("SPECIAL", 1, 200, c -> false, c -> List.of()))
                        .calculate(opposite());
        assertThat(result.selection().algorithm())
                .isEqualTo(BaselineSettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments()).hasSize(2);
    }

    @Test
    void reusesPinnedCalculatorAndPolicyVersionAfterNewHigherPriorityVersionIsRegistered() {
        var previous =
                engine(new GreedySettlementNettingCalculator()).calculate(opposite()).selection();
        var upgraded =
                calculator(
                        "GREEDY_NET_BALANCE",
                        2,
                        1000,
                        c -> true,
                        c -> List.of(new NettingPayment(1, 2, 5000)));
        var current = engine(new GreedySettlementNettingCalculator(), upgraded);
        assertThat(current.calculate(opposite()).selection().algorithm()).isEqualTo(upgraded.key());
        var pinned = new NettingCalculatorSelection(previous.algorithm(), 77);
        assertThat(current.recalculate(opposite(), pinned).selection()).isEqualTo(pinned);
        assertThat(current.recalculate(opposite(), pinned).calculation().algorithm())
                .isEqualTo("GREEDY_NET_BALANCE_V1");
    }

    @Test
    void missingVersionUnsupportedPinnedInputAndLegacySelectionRequireNewPreview() {
        assertCode(
                () ->
                        engine(new GreedySettlementNettingCalculator())
                                .recalculate(
                                        opposite(),
                                        new NettingCalculatorSelection(
                                                new NettingAlgorithmKey("GREEDY_NET_BALANCE", 2),
                                                1)),
                NettingStatusCode.NETTING_INPUT_CHANGED);
        var unsupported =
                calculator("LIMITED", 1, 1, c -> c.participantCount() == 1, c -> c.baseline());
        assertCode(
                () ->
                        engine(unsupported)
                                .recalculate(
                                        opposite(),
                                        new NettingCalculatorSelection(unsupported.key(), 1)),
                NettingStatusCode.NETTING_INPUT_CHANGED);
        assertCode(
                () -> engine(new GreedySettlementNettingCalculator()).recalculate(opposite(), null),
                NettingStatusCode.NETTING_INPUT_CHANGED);
    }

    @Test
    void nonImprovingGreedyFallsBackButRetainsSelectedIdentity() {
        var debts = List.of(debt(1, 2, 5), debt(1, 3, 3), debt(4, 5, 6));
        assertThat(
                        new GreedySettlementNettingCalculator()
                                .calculate(NettingCalculationContext.from(debts)))
                .hasSize(4);
        var result = engine(new GreedySettlementNettingCalculator()).calculate(debts);
        assertThat(result.calculation().algorithm()).isEqualTo("AGGREGATED_BASELINE_V1");
        assertThat(result.selection().algorithm()).isEqualTo(GreedySettlementNettingCalculator.KEY);
        assertThat(result.calculation().payments()).hasSize(3);
        var equal =
                engine(new GreedySettlementNettingCalculator())
                        .calculate(List.of(debt(1, 2, 5000)));
        assertThat(equal.calculation().algorithm()).isEqualTo("AGGREGATED_BASELINE_V1");
    }

    @Test
    void invalidCandidateFailsBeforeBaselineFallbackEvenIfItHasMoreTransfers() {
        assertCode(
                () ->
                        engine(
                                        calculator(
                                                "BAD_BALANCE",
                                                1,
                                                1,
                                                c -> true,
                                                c -> List.of(new NettingPayment(1, 2, 1))))
                                .calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATION_INVALID);
        var outsiders =
                calculator(
                        "OUTSIDERS",
                        1,
                        1,
                        c -> true,
                        c ->
                                List.of(
                                        new NettingPayment(1, 2, 5000),
                                        new NettingPayment(3, 4, 1),
                                        new NettingPayment(4, 3, 1)));
        assertCode(
                () -> engine(outsiders).calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATION_INVALID);
        var duplicates =
                calculator(
                        "DUPLICATES",
                        1,
                        1,
                        c -> true,
                        c ->
                                List.of(
                                        new NettingPayment(1, 2, 2500),
                                        new NettingPayment(1, 2, 2500)));
        assertCode(
                () -> engine(duplicates).calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATION_INVALID);
        assertCode(
                () ->
                        engine(calculator("NULL_RESULT", 1, 1, c -> true, c -> null))
                                .calculate(opposite()),
                NettingStatusCode.NETTING_CALCULATION_INVALID);
    }

    @Test
    void normalizesValidCandidateOrderAndProtectsImmutableContext() {
        var debts = List.of(debt(1, 2, 8), debt(1, 3, 2), debt(2, 3, 3));
        var result =
                engine(
                                calculator(
                                        "UNSORTED",
                                        1,
                                        1,
                                        c -> true,
                                        c ->
                                                List.of(
                                                        new NettingPayment(1, 3, 5),
                                                        new NettingPayment(1, 2, 5))))
                        .calculate(debts);
        assertThat(result.calculation().payments())
                .containsExactly(new NettingPayment(1, 2, 5), new NettingPayment(1, 3, 5));
        var source = new ArrayList<>(debts);
        var context = NettingCalculationContext.from(source);
        source.clear();
        assertThat(context.debts()).hasSize(3);
        assertThatThrownBy(() -> context.debts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.balances().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
