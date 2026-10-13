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
    public RecapSummary summarize(List<RecapSourceFact> facts) {
        Map<UUID, Long> amounts = new HashMap<>();
        for (RecapSourceFact fact : facts) {
            if (fact.ownAmount() == null && !fact.validZeroSubmission()) {
                throw new IllegalStateException("Incomplete own share in completed settlement");
            }
            long amount = fact.ownAmount() == null ? 0 : fact.ownAmount();
            if (amount < 0) {
                throw new IllegalStateException("Negative own share");
            }
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
