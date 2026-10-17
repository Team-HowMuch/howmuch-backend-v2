package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;

import java.util.Objects;

/** Persisted selection, distinct from the final result's baseline fallback algorithm. */
public record NettingCalculatorSelection(NettingAlgorithmKey algorithm, int policyVersion) {
    public NettingCalculatorSelection {
        Objects.requireNonNull(algorithm, "Calculator algorithm is required");
        if (policyVersion <= 0)
            throw new IllegalArgumentException("Invalid selection policy version");
    }
}
