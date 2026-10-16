package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCalculatorSelectionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NettingCalculatorSelector {
    private final Map<NettingAlgorithmKey, SettlementNettingCalculator> calculators;
    private final NettingCalculatorSelectionPolicy policy;
    private final NettingCalculatorFeatures features;

    public record PreparedSelection(
            NettingCalculatorSelection selection, PreparedNettingCalculator calculator) {}

    public NettingCalculatorSelector(
            List<SettlementNettingCalculator> calculators,
            NettingCalculatorSelectionPolicy policy) {
        this(calculators, policy, new NettingCalculatorFeatures(true));
    }

    @Autowired
    public NettingCalculatorSelector(
            List<SettlementNettingCalculator> calculators,
            NettingCalculatorSelectionPolicy policy,
            NettingCalculatorFeatures features) {
        var registered = new LinkedHashMap<NettingAlgorithmKey, SettlementNettingCalculator>();
        for (var calculator : calculators) {
            var key =
                    java.util.Objects.requireNonNull(
                            calculator.key(), "Calculator key is required");
            if (registered.putIfAbsent(key, calculator) != null)
                throw new IllegalArgumentException(
                        "Duplicate netting calculator: " + key.identifier());
        }
        this.calculators = Map.copyOf(registered);
        this.policy = policy;
        this.features = features;
    }

    public NettingCalculatorSelection select(NettingCalculationContext context) {
        return select(context, true);
    }

    /** Selects among supported calculators, optionally omitting both exact optimizers. */
    public NettingCalculatorSelection select(
            NettingCalculationContext context, boolean includeExactOptimizers) {
        return prepareSelection(context, includeExactOptimizers, null).selection();
    }

    public PreparedSelection prepareSelection(
            NettingCalculationContext context,
            boolean includeExactOptimizers,
            List<NettingPayment> greedyCandidate) {
        boolean pairEnabled = features.pairReductionEnabled();
        var prepared = new LinkedHashMap<NettingAlgorithmKey, PreparedNettingCalculator>();
        for (var entry : calculators.entrySet()) {
            var key = entry.getKey();
            if (!includeExactOptimizers
                    && (key.equals(BitmaskDpSettlementNettingCalculator.KEY)
                            || key.equals(PairReductionSettlementNettingCalculator.KEY))) continue;
            if (!pairEnabled && key.equals(PairReductionSettlementNettingCalculator.KEY)) continue;
            entry.getValue()
                    .prepare(context, greedyCandidate)
                    .ifPresent(work -> prepared.put(key, work));
        }
        var supported =
                prepared.entrySet().stream()
                        .map(
                                e ->
                                        new NettingCalculatorSelectionPolicy.Candidate(
                                                e.getKey(), e.getValue().priority()))
                        .toList();
        var key =
                policy.select(supported)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                NettingStatusCode.NETTING_CALCULATOR_UNAVAILABLE));
        int version =
                pairEnabled
                        ? NettingCalculatorSelectionPolicy.VERSION
                        : NettingCalculatorSelectionPolicy.LEGACY_VERSION;
        return new PreparedSelection(
                new NettingCalculatorSelection(key, version), prepared.get(key));
    }

    public Optional<SettlementNettingCalculator> findSupported(
            NettingCalculationContext context, NettingAlgorithmKey key) {
        var calculator = calculators.get(key);
        return calculator != null && calculator.supports(context)
                ? Optional.of(calculator)
                : Optional.empty();
    }

    public SettlementNettingCalculator resolve(
            NettingCalculationContext context, NettingCalculatorSelection selection) {
        if (selection == null)
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        var calculator = calculators.get(selection.algorithm());
        if (calculator == null || !calculator.supports(context))
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        return calculator;
    }

    /** Pinned selection is independent of today's automatic-selection flag and policy version. */
    public PreparedNettingCalculator resolvePrepared(
            NettingCalculationContext context, NettingCalculatorSelection selection) {
        if (selection == null)
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        var calculator = calculators.get(selection.algorithm());
        if (calculator == null)
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        return calculator
                .prepare(context, null)
                .orElseThrow(
                        () -> ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED));
    }
}
