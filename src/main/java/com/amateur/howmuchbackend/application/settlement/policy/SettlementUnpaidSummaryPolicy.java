package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementUnpaidSummaryReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class SettlementUnpaidSummaryPolicy {
    public SettlementUnpaidSummaryResult summarize(List<SettlementUnpaidSummaryReadModel> facts) {
        long remainingAmount = 0;
        int unpaidCount = 0;
        Set<UUID> unconfirmedSettlementIds = new HashSet<>();

        for (var fact : facts) {
            if (fact.status() == SettlementStatus.REQUESTED
                    && fact.allocationType() == SettlementAllocationType.ITEM_ALLOCATION) {
                unconfirmedSettlementIds.add(fact.settlementId());
            }
            if (fact.shareAmount() == null) {
                continue;
            }
            BigInteger remaining = BigInteger.valueOf(fact.shareAmount())
                    .subtract(fact.confirmedAmount())
                    .max(BigInteger.ZERO);
            if (remaining.signum() > 0) {
                remainingAmount = Math.addExact(remainingAmount, remaining.longValueExact());
                unpaidCount = Math.incrementExact(unpaidCount);
            }
        }
        return new SettlementUnpaidSummaryResult(
                remainingAmount, unpaidCount, unconfirmedSettlementIds.size()
        );
    }
}
