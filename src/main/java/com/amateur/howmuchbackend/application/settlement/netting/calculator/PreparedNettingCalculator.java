package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import java.util.List;
import java.util.function.Supplier;

/** Request-local work; only the algorithm selection, never this execution closure, is persisted. */
public record PreparedNettingCalculator(
        NettingAlgorithmKey key, int priority, Supplier<List<NettingPayment>> calculation) {
    public List<NettingPayment> calculate() {
        return calculation.get();
    }
}
