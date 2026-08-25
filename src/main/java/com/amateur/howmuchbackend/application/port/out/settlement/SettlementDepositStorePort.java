package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SettlementDepositStorePort {
    List<SettlementDeposit> findAllBySettlementId(UUID settlementId);

    Set<String> findMatchedExternalTransactionKeysByReceivingAccount(
            Long creatorId,
            Long sourceAccountId,
            String organization,
            String accountNumber,
            Collection<String> externalTransactionKeys
    );

    List<PayableDepositCandidate> findUnfinishedPayableCandidatesByReceivingAccount(
            Long creatorId,
            Long sourceAccountId,
            String organization,
            String accountNumber
    );

    void saveAll(List<SettlementDeposit> deposits);

    record PayableDepositCandidate(
            UUID settlementId,
            UUID shareId,
            String payerName,
            long remainingAmount,
            LocalDateTime snapshotCreatedAt
    ) {
    }
}
