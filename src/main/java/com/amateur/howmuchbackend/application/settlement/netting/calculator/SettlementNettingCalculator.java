package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import java.util.List;
import java.util.Optional;

/**
 * Interchangeable, deterministic candidate algorithms; common safety checks belong to the engine.
 */
public interface SettlementNettingCalculator {
    NettingAlgorithmKey key();

    int priority();

    boolean supports(NettingCalculationContext context);

    List<NettingPayment> calculate(NettingCalculationContext context);

    /** Reuses request-local analysis and the Engine's validated Greedy candidate when needed. */
    default Optional<PreparedNettingCalculator> prepare(
            NettingCalculationContext context, List<NettingPayment> greedyCandidate) {
        return supports(context)
                ? Optional.of(
                        new PreparedNettingCalculator(key(), priority(), () -> calculate(context)))
                : Optional.empty();
    }
}
