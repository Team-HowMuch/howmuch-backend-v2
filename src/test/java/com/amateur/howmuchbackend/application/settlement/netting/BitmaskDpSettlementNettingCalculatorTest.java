package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.BitmaskDpSettlementNettingCalculator;
import com.amateur.howmuchbackend.application.settlement.netting.calculator.GreedySettlementNettingCalculator;
import com.amateur.howmuchbackend.application.settlement.netting.calculator.NettingCalculationContext;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

class BitmaskDpSettlementNettingCalculatorTest {
    private final BitmaskDpSettlementNettingCalculator calculator =
            new BitmaskDpSettlementNettingCalculator();

    private NettingCalculationContext context(Map<Long, Long> balances) {
        return new NettingCalculationContext(List.of(), List.of(), balances);
    }

    @Test
    void reducesTheGreedyCounterexampleFromFiveTransfersToFour() {
        var context =
                context(
                        Map.of(
                                1L, 80_000L,
                                2L, 40_000L,
                                3L, 20_000L,
                                4L, -60_000L,
                                5L, -50_000L,
                                6L, -30_000L));
        var greedy = new GreedySettlementNettingCalculator().calculate(context);
        var optimized = calculator.calculate(context);

        assertThat(greedy).hasSize(5);
        assertThat(optimized).hasSize(4);
        assertThat(optimized).isEqualTo(calculator.calculate(context));
        assertBalances(context.balances(), optimized);
    }

    @Test
    void handlesSingleDebtOppositeBalancesAndAllZeroBalances() {
        assertThat(calculator.calculate(context(Map.of(1L, -75L, 2L, 75L))))
                .containsExactly(new NettingPayment(1, 2, 75));
        assertThat(calculator.calculate(context(Map.of(1L, 0L, 2L, 0L)))).isEmpty();
    }

    @Test
    void irregularSmallAmountsProduceThreeValidTransfersWithGreedyAndDp() {
        var context = context(Map.of(1L, 5_000L, 2L, 40L, 3L, -3_000L, 4L, -2_040L));
        var greedy = new GreedySettlementNettingCalculator().calculate(context);
        var optimized = calculator.calculate(context);

        assertThat(greedy).hasSize(3);
        assertThat(optimized).hasSize(3);
        assertBalances(context.balances(), greedy);
        assertBalances(context.balances(), optimized);
    }

    @Test
    void supportsAtMostTwelveNonZeroBalancesAndIgnoresZeroBalancesForItsLimit() {
        var twelve = new TreeMap<Long, Long>();
        for (long userId = 1; userId <= 6; userId++) twelve.put(userId, 10L);
        for (long userId = 7; userId <= 12; userId++) twelve.put(userId, -10L);
        twelve.put(100L, 0L);
        assertThat(calculator.supports(context(twelve))).isTrue();
        assertThat(calculator.calculate(context(twelve))).hasSize(6);

        var thirteen = new TreeMap<>(twelve);
        thirteen.remove(100L);
        thirteen.put(12L, -20L);
        thirteen.put(13L, 10L);
        assertThat(calculator.supports(context(thirteen))).isFalse();
        assertThatThrownBy(() -> calculator.calculate(context(thirteen)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnbalancedAndUnrepresentableBalancesWithoutWrapping() {
        assertThatThrownBy(() -> calculator.calculate(context(Map.of(1L, 7L, 2L, -6L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to zero");
        assertThatThrownBy(
                        () ->
                                calculator.calculate(
                                        context(
                                                Map.of(
                                                        1L, Long.MIN_VALUE,
                                                        2L, Long.MAX_VALUE,
                                                        3L, 1L))))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(
                        () ->
                                calculator.calculate(
                                        context(
                                                Map.of(
                                                        1L,
                                                        Long.MAX_VALUE,
                                                        2L,
                                                        1L,
                                                        3L,
                                                        -Long.MAX_VALUE,
                                                        4L,
                                                        -1L))))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void matchesIndependentPartitionOracleForSeededBalancedInputs() {
        Random random = new Random(507);
        for (int run = 0; run < 250; run++) {
            int participantCount = random.nextInt(7) + 2;
            long[] balances = randomBalancedBalances(random, participantCount);
            var map = new HashMap<Long, Long>();
            for (int i = 0; i < balances.length; i++) map.put((long) i + 1, balances[i]);
            var context = context(map);
            var payments = calculator.calculate(context);
            var greedyPayments = new GreedySettlementNettingCalculator().calculate(context);

            assertThat(payments).hasSize(participantCount - oracleMaxZeroSumGroups(balances));
            assertThat(payments).hasSizeLessThanOrEqualTo(greedyPayments.size());
            assertThat(payments).isEqualTo(calculator.calculate(context));
            assertBalances(context.balances(), payments);
        }
    }

    @Test
    void outputIsOrderedByUserIdsForEqualBalanceChoices() {
        var context = context(Map.of(9L, 10L, 2L, 10L, 8L, -10L, 1L, -10L));
        assertThat(calculator.calculate(context))
                .containsExactly(new NettingPayment(1, 2, 10), new NettingPayment(8, 9, 10));
    }

    private long[] randomBalancedBalances(Random random, int count) {
        while (true) {
            long[] values = new long[count];
            long sum = 0;
            for (int i = 0; i < count - 1; i++) {
                do {
                    values[i] = random.nextInt(31) - 15;
                } while (values[i] == 0);
                sum += values[i];
            }
            values[count - 1] = -sum;
            if (values[count - 1] == 0) continue;
            return values;
        }
    }

    private int oracleMaxZeroSumGroups(long[] balances) {
        int stateCount = 1 << balances.length;
        long[] sums = new long[stateCount];
        for (int mask = 1; mask < stateCount; mask++) {
            int bit = Integer.numberOfTrailingZeros(mask);
            sums[mask] = sums[mask & (mask - 1)] + balances[bit];
        }
        int[] memo = new int[stateCount];
        java.util.Arrays.fill(memo, Integer.MIN_VALUE);
        memo[0] = 0;
        return oracleMaxZeroSumGroups(stateCount - 1, sums, memo);
    }

    private int oracleMaxZeroSumGroups(int remaining, long[] sums, int[] memo) {
        if (memo[remaining] != Integer.MIN_VALUE) return memo[remaining];
        int firstBit = Integer.numberOfTrailingZeros(remaining);
        int first = 1 << firstBit;
        int best = 0;
        for (int subset = remaining; subset != 0; subset = (subset - 1) & remaining) {
            if ((subset & first) != 0 && sums[subset] == 0) {
                best = Math.max(best, 1 + oracleMaxZeroSumGroups(remaining ^ subset, sums, memo));
            }
        }
        memo[remaining] = best;
        return best;
    }

    private void assertBalances(Map<Long, Long> expected, List<NettingPayment> payments) {
        Map<Long, Long> actual = new TreeMap<>();
        for (var payment : payments) {
            assertThat(payment.payerId()).isNotEqualTo(payment.receiverId());
            assertThat(payment.amount()).isPositive();
            actual.merge(payment.payerId(), -payment.amount(), Math::addExact);
            actual.merge(payment.receiverId(), payment.amount(), Math::addExact);
        }
        actual.values().removeIf(balance -> balance == 0);
        var normalizedExpected = new TreeMap<>(expected);
        normalizedExpected.values().removeIf(balance -> balance == 0);
        assertThat(actual).isEqualTo(normalizedExpected);
    }
}
