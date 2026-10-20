package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingEstimateCachePort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

@Component
public class NettingEstimateInvalidationService {
    private final LoadSettlementParticipantPort participants;
    private final NettingEstimateCachePort cache;
    private final boolean enabled;

    public NettingEstimateInvalidationService(
            LoadSettlementParticipantPort participants,
            NettingEstimateCachePort cache,
            @Value("${howmuch.settlement.netting.enabled:false}") boolean enabled) {
        this.participants = participants;
        this.cache = cache;
        this.enabled = enabled;
    }

    public void settlementChanged(UUID settlementId) {
        if (!enabled) return;
        var groups = new LinkedHashSet<Long>();
        for (var participant : participants.findAllBySettlementId(settlementId))
            if (participant.sourceGroupId() != null) groups.add(participant.sourceGroupId());
        schedule(groups);
    }

    public void groupChanged(long groupId) {
        if (enabled) schedule(Set.of(groupId));
    }

    private void schedule(Set<Long> groups) {
        if (groups.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            groups.forEach(cache::invalidate);
            return;
        }
        for (var sync : TransactionSynchronizationManager.getSynchronizations())
            if (sync instanceof Invalidation invalidation && invalidation.owner() == this) {
                invalidation.groups.addAll(groups);
                return;
            }
        TransactionSynchronizationManager.registerSynchronization(new Invalidation(groups));
    }

    private final class Invalidation implements TransactionSynchronization {
        private final Set<Long> groups;

        private Invalidation(Set<Long> groups) {
            this.groups = new LinkedHashSet<>(groups);
        }

        private NettingEstimateInvalidationService owner() {
            return NettingEstimateInvalidationService.this;
        }

        @Override
        public void afterCommit() {
            groups.forEach(cache::invalidate);
        }
    }
}
