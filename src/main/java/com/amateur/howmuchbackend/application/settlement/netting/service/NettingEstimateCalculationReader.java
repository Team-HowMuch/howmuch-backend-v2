package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort.Counts;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class NettingEstimateCalculationReader {
    private final GroupNettingInputReader inputs;
    private final MeterRegistry metrics;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Counts calculate(long groupId) {
        long started = System.nanoTime();
        metrics.counter("settlement.netting.estimate.calculation.count").increment();
        try {
            var input = inputs.readForEstimate(groupId);
            int before =
                    Math.toIntExact(
                            input.debts().stream().filter(d -> d.remainingAmount() > 0).count());
            return new Counts(
                    input.sources().size(),
                    before,
                    input.calculation().payments().size(),
                    input.compressionTargetCount());
        } catch (ApplicationException noDebts) {
            if (noDebts.getStatusCode() != NettingStatusCode.NETTING_NO_ELIGIBLE_DEBTS)
                throw noDebts;
            return new Counts(0, 0, 0, 0);
        } finally {
            metrics.timer("settlement.netting.estimate.calculation.duration")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
