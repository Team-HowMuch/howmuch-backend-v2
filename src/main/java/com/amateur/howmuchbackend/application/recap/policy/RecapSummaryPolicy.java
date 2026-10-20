package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import com.amateur.howmuchbackend.application.recap.model.RecapSummary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RecapSummaryPolicy {
    public boolean isConfirmedZero(RecapSourceFact fact, boolean allocationValid,
                                   List<com.amateur.howmuchbackend.domain.settlement.SettlementShare> calculatedShares) {
        var allocation = fact.allocation();
        return allocation != null && allocation.ownParticipantId() != null && allocation.ownSubmitted()
                && !allocation.items().isEmpty() && allocation.submissionCount() == allocation.participants().size()
                && allocationValid && calculatedShares.stream()
                .noneMatch(share -> share.payerParticipantId().equals(allocation.ownParticipantId()));
    }

    public RecapSummary summarize(List<RecapSourceFact> facts) {
        Map<UUID, Long> amounts = new HashMap<>();
        for (RecapSourceFact fact : facts) {
            long amount = fact.confirmedOwnAmount();
            Long previous = amounts.putIfAbsent(fact.settlementId(), amount);
            if (previous != null && previous != amount) {
                throw new IllegalStateException("Inconsistent own share");
            }
        }
        long total = 0;
        for (long amount : amounts.values()) {
            total = Math.addExact(total, amount);
        }
        return new RecapSummary(amounts.size(), total);
    }
}
