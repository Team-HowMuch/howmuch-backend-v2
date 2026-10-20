package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.GetGroupNettingEstimateUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingEstimateResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingCompressionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class GroupNettingEstimateService implements GetGroupNettingEstimateUseCase {
    private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);
    private final CurrentActorPort actor;
    private final GroupAccessService groups;
    private final NettingEstimateCalculationReader calculation;
    private final NettingEstimateCachePort cache;
    private final NettingTransferAvailabilityPort availability;
    private final LoadGroupNettingDebtPort debts;
    private final MeterRegistry metrics;
    private final NettingCompressionPolicy compression;

    @Override
    public GroupNettingEstimateResult estimate(long groupId) {
        long started = System.nanoTime();
        String outcome = "error";
        try {
            long userId = actor.getCurrentActor().userId();
            groups.loadActive(groupId);
            groups.requireActiveMember(groupId, userId);
            if (debts.hasOpenReviews(groupId))
                throw ApplicationException.of(NettingStatusCode.NETTING_ALREADY_ACTIVE);
            var lookup = counts(groupId);
            outcome = lookup.outcome();
            var counts = lookup.counts();
            // These mutable actor/status facts are deliberately never cached with group counts.
            groups.loadActive(groupId);
            groups.requireActiveMember(groupId, userId);
            if (debts.hasOpenReviews(groupId))
                throw ApplicationException.of(NettingStatusCode.NETTING_ALREADY_ACTIVE);
            var reason = compression.unavailableReason(
                    counts.compressionTargetCount(), counts.beforeTransferCount(), counts.afterTransferCount(),
                    availability.isGroupReplacementPending(groupId));
            return new GroupNettingEstimateResult(
                    groupId,
                    counts.eligibleSettlementCount(),
                    counts.beforeTransferCount(),
                    counts.afterTransferCount(),
                    counts.reducedTransferCount(),
                    reason == null,
                    reason);
        } catch (ApplicationException failure) {
            if (failure.getStatusCode() == NettingStatusCode.NETTING_ESTIMATE_BUSY)
                outcome = "busy";
            else if (failure.getStatusCode() == NettingStatusCode.NETTING_ESTIMATE_UNAVAILABLE)
                outcome = "unavailable";
            throw failure;
        } finally {
            metrics.timer("settlement.netting.estimate.request.duration", "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private record Lookup(NettingEstimateCachePort.Counts counts, String outcome) {}

    private Lookup counts(long groupId) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        boolean missed = false;
        do {
            var stored = cache.find(groupId);
            if (stored.isPresent()) {
                outcome(missed ? "shared" : "hit");
                return new Lookup(stored.get(), missed ? "shared" : "hit");
            }
            if (!missed) {
                outcome("miss");
                missed = true;
            }
            var claim = cache.tryClaim(groupId);
            if (claim.isPresent()) {
                try {
                    var result = calculation.calculate(groupId);
                    if (cache.publish(groupId, claim.get(), result))
                        return new Lookup(result, "miss");
                } finally {
                    cache.release(groupId, claim.get());
                }
            }
            outcome("wait");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            if (Thread.currentThread().isInterrupted()) break;
        } while (System.nanoTime() < deadline);
        outcome("busy");
        throw ApplicationException.of(NettingStatusCode.NETTING_ESTIMATE_BUSY);
    }

    private void outcome(String outcome) {
        metrics.counter("settlement.netting.estimate.cache", "outcome", outcome).increment();
    }
}
