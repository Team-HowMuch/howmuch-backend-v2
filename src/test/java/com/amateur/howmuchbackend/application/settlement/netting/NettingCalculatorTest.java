package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCalculatorSelectionPolicy;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingCalculationEngine;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingCalculatorSelector;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class NettingCalculatorTest {
    private final NettingCalculationEngine engine =
            new NettingCalculationEngine(
                    new NettingCalculatorSelector(
                            List.of(
                                    new GreedySettlementNettingCalculator(),
                                    new BaselineSettlementNettingCalculator()),
                            new NettingCalculatorSelectionPolicy()),
                    new NettingResultValidator());

    private NettingCalculation calculate(List<NettingDebt> debts) {
        return engine.calculate(debts).calculation();
    }

    private final NettingResultValidator validator = new NettingResultValidator();

    private NettingDebt debt(long payer, long receiver, long amount, long confirmed) {
        return new NettingDebt(
                UUID.randomUUID(), UUID.randomUUID(), payer, receiver, amount, confirmed);
    }

    @Test
    void oppositeDebtsPreserveActualDeposits() {
        var debts = List.of(debt(1, 2, 15000, 0), debt(2, 1, 10000, 0));
        var result = calculate(debts);
        validator.validate(debts, result);
        assertThat(result.payments()).containsExactly(new NettingPayment(1, 2, 5000));
        var partial = List.of(debt(1, 2, 15000, 3000), debt(2, 1, 10000, 0));
        assertThat(calculate(partial).payments()).containsExactly(new NettingPayment(1, 2, 2000));
    }

    @Test
    void counterExampleFallsBackWithoutIncreasingTransferCount() {
        var debts = List.of(debt(1, 2, 5, 0), debt(1, 3, 3, 0), debt(4, 5, 6, 0));
        var result = calculate(debts);
        validator.validate(debts, result);
        assertThat(result.algorithm()).isEqualTo("AGGREGATED_BASELINE_V1");
        assertThat(result.payments()).hasSize(3);
    }

    @Test
    void cycleNeedsNoTransfers() {
        var debts = List.of(debt(1, 2, 100, 0), debt(2, 3, 100, 0), debt(3, 1, 100, 0));
        var result = calculate(debts);
        validator.validate(debts, result);
        assertThat(result.payments()).isEmpty();
    }

    @Test
    void randomizedGraphsPreserveBalancesAndAreOrderIndependent() {
        Random random = new Random(506);
        for (int run = 0; run < 5000; run++) {
            List<NettingDebt> debts = new ArrayList<>();
            for (int i = 0; i < random.nextInt(50) + 1; i++) {
                long payer = random.nextInt(12) + 1, receiver = random.nextInt(12) + 1;
                if (payer == receiver) continue;
                long amount = random.nextInt(100000) + 1;
                debts.add(debt(payer, receiver, amount, random.nextLong(amount + 1)));
            }
            var result = calculate(debts);
            validator.validate(debts, result);
            Collections.shuffle(debts, random);
            assertThat(calculate(debts)).isEqualTo(result);
        }
    }

    @Test
    void overflowNeverWrapsDebt() {
        assertThatThrownBy(
                        () -> calculate(List.of(debt(1, 2, Long.MAX_VALUE, 0), debt(1, 2, 1, 0))))
                .isInstanceOf(ArithmeticException.class);
    }
}
