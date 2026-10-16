package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Production eligibility around the existing experimental calculation core. */
@Component
public final class PairReductionSettlementNettingCalculator implements SettlementNettingCalculator {
    public static final NettingAlgorithmKey KEY = new NettingAlgorithmKey("PAIR_REDUCED_DP_NET", 1);

    private final PairReducedDpNettingCalculator core = new PairReducedDpNettingCalculator();

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
        return prepare(context, null).isPresent();
    }

    @Override
    public List<NettingPayment> calculate(NettingCalculationContext context) {
        return prepare(context, null)
                .orElseThrow(() -> new IllegalArgumentException("Pair DP input is unsupported"))
                .calculate();
    }

    @Override
    public Optional<PreparedNettingCalculator> prepare(
            NettingCalculationContext context, List<NettingPayment> greedyCandidate) {
        if (context == null) return Optional.empty();
        var reduction = core.analyzeReduction(context);
        if (reduction.exactPairs().isEmpty()
                || reduction.residualNonZeroBalances()
                        > BitmaskDpSettlementNettingCalculator.MAX_NON_ZERO_PARTICIPANTS) {
            return Optional.empty();
        }
        return Optional.of(
                new PreparedNettingCalculator(
                        key(),
                        priority(),
                        () -> core.calculatePrepared(context, greedyCandidate, reduction)));
    }
}
