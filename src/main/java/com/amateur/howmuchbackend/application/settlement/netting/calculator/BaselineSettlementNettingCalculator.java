package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BaselineSettlementNettingCalculator implements SettlementNettingCalculator {
    public static final NettingAlgorithmKey KEY = new NettingAlgorithmKey("AGGREGATED_BASELINE", 1);

    @Override
    public NettingAlgorithmKey key() {
        return KEY;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean supports(NettingCalculationContext context) {
        return true;
    }

    @Override
    public List<NettingPayment> calculate(NettingCalculationContext context) {
        return context.baseline();
    }
}
