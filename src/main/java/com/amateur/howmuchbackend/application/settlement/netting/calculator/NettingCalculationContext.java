package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingDebt;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Immutable calculation facts. Calculators must not consult clocks, randomness or external state.
 */
public record NettingCalculationContext(
        List<NettingDebt> debts, List<NettingPayment> baseline, Map<Long, Long> balances) {
    public NettingCalculationContext {
        debts = List.copyOf(debts);
        baseline = List.copyOf(baseline);
        balances = Map.copyOf(balances);
    }

    public static NettingCalculationContext from(List<NettingDebt> debts) {
        var snapshot = List.copyOf(debts);
        var baseline = NettingBalanceSupport.baseline(snapshot);
        return new NettingCalculationContext(
                snapshot, baseline, NettingBalanceSupport.balances(baseline));
    }

    public int debtCount() {
        return debts.size();
    }

    /** Counts original debt endpoints, including participants whose net balance is zero. */
    public long participantCount() {
        return debts.stream()
                .flatMap(d -> Stream.of(d.payerId(), d.receiverId()))
                .distinct()
                .count();
    }
}
