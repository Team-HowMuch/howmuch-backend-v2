package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementSuspensionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class SettlementSuspensionWriter {
    private final LoadAccountPort loadAccountPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementReceivingAccountSnapshotPort snapshotPort;
    private final LoadSettlementParticipantPort participantPort;
    private final LoadSettlementSharePort sharePort;
    private final SettlementItemAllocationStorePort allocationStorePort;
    private final SettlementDepositReader depositReader;
    private final SettlementDepositStorePort depositStorePort;
    private final SettlementSuspensionPolicy suspensionPolicy;
    private final SaveSettlementPort saveSettlementPort;
    private final NettingEstimateInvalidationService estimateInvalidation;

    @Transactional
    public SettlementResult suspend(
            SettlementSuspensionContext expected,
            Long userId,
            LocalDateTime refreshedThrough
    ) {
        SettlementReceivingAccountSnapshot expectedSnapshot = expected.snapshot();
        if (expectedSnapshot != null) {
            loadAccountPort.findActiveByIdAndUserIdForUpdate(
                    expectedSnapshot.sourceAccountId(),
                    userId
            ).orElseThrow(() -> ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE));
        }

        Settlement settlement = loadSettlementPort.findByIdForUpdate(expected.settlement().id())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (!settlement.equals(expected.settlement())) {
            if (expected.settlement().status() == SettlementStatus.AWAITING_DEPOSIT
                    && settlement.status() == SettlementStatus.COMPLETED) {
                throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE);
            }
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }

        SettlementReceivingAccountSnapshot currentSnapshot = snapshotPort
                .findBySettlementId(settlement.id())
                .orElse(null);
        List<SettlementParticipant> currentParticipants =
                participantPort.findAllBySettlementId(settlement.id());
        List<SettlementShare> currentShares = sharePort.findAllBySettlementId(settlement.id());
        List<SettlementItemSubmission> currentSubmissions =
                allocationStorePort.findAllSubmissions(settlement.id());
        if (!Objects.equals(expectedSnapshot, currentSnapshot)
                || !sameEntities(expected.participants(), currentParticipants)
                || !sameEntities(expected.shares(), currentShares)
                || !sameEntities(expected.submissions(), currentSubmissions)) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }

        boolean requiresRefresh = suspensionPolicy.requiresDepositRefresh(
                settlement,
                currentShares,
                currentParticipants,
                currentSubmissions
        );
        if (requiresRefresh != (refreshedThrough != null)) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
        if (requiresRefresh) {
            LocalDateTime finalNow = LocalDateTime.now(ZoneOffset.UTC);
            SettlementDepositReader.DepositData data = depositReader.load(
                    settlement,
                    finalNow
            );
            if (!expectedSnapshot.equals(data.snapshot())) {
                throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
            }
            List<SettlementDeposit> deposits = depositStorePort
                    .findAllBySettlementId(settlement.id());
            Set<String> transactionKeys = data.transactions().stream()
                    .map(Transaction::externalTransactionKey)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> matchedKeys = transactionKeys.isEmpty()
                    ? Set.of()
                    : depositStorePort.findMatchedExternalTransactionKeysByReceivingAccount(
                            settlement.creatorId(),
                            expectedSnapshot.sourceAccountId(),
                            expectedSnapshot.organization(),
                            expectedSnapshot.accountNumber(),
                            transactionKeys
                    );
            boolean hasUnmatchedPositiveIncome = data.transactions().stream()
                    .filter(transaction -> transaction.amount() != null
                            && transaction.amount().signum() > 0)
                    .anyMatch(transaction -> transaction.externalTransactionKey() == null
                            || !matchedKeys.contains(transaction.externalTransactionKey()));
            suspensionPolicy.validateNoPayments(
                    !deposits.isEmpty(),
                    hasUnmatchedPositiveIncome
            );
        }

        var saved=saveSettlementPort.update(settlement.suspendManually(LocalDateTime.now(ZoneOffset.UTC)));
        estimateInvalidation.settlementChanged(settlement.id());
        return SettlementResult.from(saved);
    }

    private boolean sameEntities(Collection<?> left, Collection<?> right) {
        return new HashSet<>(left).equals(new HashSet<>(right));
    }
}
