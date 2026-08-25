package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SettlementDepositStorePort {
    List<SettlementDeposit> findAllBySettlementId(UUID settlementId);

    Set<Long> findMatchedTransactionIds(Collection<Long> transactionIds);

    List<PayableDepositCandidate> findUnfinishedPayableCandidatesBySourceAccountId(
            Long sourceAccountId
    );

    void saveAll(List<SettlementDeposit> deposits);

    record PayableDepositCandidate(
            UUID settlementId,
            UUID shareId,
            String payerName,
            long remainingAmount
    ) {
    }
}
