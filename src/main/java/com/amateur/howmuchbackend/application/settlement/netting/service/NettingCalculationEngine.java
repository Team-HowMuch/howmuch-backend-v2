package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NettingCalculationEngine {
    public record Decision(NettingCalculatorSelection selection, NettingCalculation calculation) {}

    private final NettingCalculatorSelector selector;
    private final NettingResultValidator validator;

    public Decision calculate(List<NettingDebt> debts) {
        var context = NettingCalculationContext.from(debts);
        try {
            var greedy = selector.findSupported(context, GreedySettlementNettingCalculator.KEY);
            List<NettingPayment> greedyCandidate =
                    greedy.map(calculator -> calculateGreedyCandidate(context, calculator))
                            .orElse(null);
            boolean includeExactOptimizers =
                    greedyCandidate == null
                            || greedyCandidate.size() != greedyTransferLowerBound(context);
            var prepared =
                    selector.prepareSelection(context, includeExactOptimizers, greedyCandidate);
            var selection = prepared.selection();
            var precomputedGreedy =
                    selection.algorithm().equals(GreedySettlementNettingCalculator.KEY)
                            ? greedyCandidate
                            : null;
            return execute(context, selection, prepared.calculator(), precomputedGreedy);
        } catch (IllegalArgumentException
                | ArithmeticException
                | NullPointerException
                | IllegalStateException invalid) {
            throw ApplicationException.of(NettingStatusCode.NETTING_CALCULATION_INVALID);
        }
    }

    public Decision recalculate(List<NettingDebt> debts, NettingCalculatorSelection pinned) {
        if (pinned == null) throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        var context = NettingCalculationContext.from(debts);
        try {
            return execute(context, pinned, selector.resolvePrepared(context, pinned), null);
        } catch (IllegalArgumentException
                | ArithmeticException
                | NullPointerException
                | IllegalStateException invalid) {
            throw ApplicationException.of(NettingStatusCode.NETTING_CALCULATION_INVALID);
        }
    }

    private Decision execute(
            NettingCalculationContext context,
            NettingCalculatorSelection selection,
            PreparedNettingCalculator calculator,
            List<NettingPayment> precomputedGreedy) {
        var payments =
                List.copyOf(precomputedGreedy == null ? calculator.calculate() : precomputedGreedy)
                        .stream()
                        .sorted(
                                Comparator.comparingLong(NettingPayment::payerId)
                                        .thenComparingLong(NettingPayment::receiverId))
                        .toList();
        var candidate =
                new NettingCalculation(
                        selection.algorithm().identifier(), context.baseline().size(), payments);
        validator.validateCandidate(context, candidate);
        var result =
                payments.size() < context.baseline().size()
                        ? candidate
                        : new NettingCalculation(
                                BaselineSettlementNettingCalculator.KEY.identifier(),
                                context.baseline().size(),
                                context.baseline());
        validator.validate(context, result);
        return new Decision(selection, result);
    }

    private List<NettingPayment> calculateGreedyCandidate(
            NettingCalculationContext context, SettlementNettingCalculator calculator) {
        var payments = List.copyOf(calculator.calculate(context));
        validator.validateCandidate(
                context,
                new NettingCalculation(
                        calculator.key().identifier(), context.baseline().size(), payments));
        return payments;
    }

    private int greedyTransferLowerBound(NettingCalculationContext context) {
        long debtors = context.balances().values().stream().filter(balance -> balance < 0).count();
        long creditors =
                context.balances().values().stream().filter(balance -> balance > 0).count();
        return Math.toIntExact(Math.max(debtors, creditors));
    }
}
