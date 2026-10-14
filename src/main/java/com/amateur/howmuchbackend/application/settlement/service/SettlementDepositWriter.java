package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDepositMatchingPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class SettlementDepositWriter {
    private final LoadAccountPort loadAccountPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveAccountTransactionPort saveTransactionPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SettlementDepositStorePort depositStorePort;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementDepositMatchingPolicy matchingPolicy;
    private final SettlementDepositReader reader;
    private final BusinessNotificationService notifications;

    @Transactional
    public SettlementDepositSummaryResult reconcile(
            UUID settlementId,
            Long userId,
            SettlementReceivingAccountSnapshot expectedSnapshot,
            List<Transaction> fetchedTransactions,
            LocalDateTime now
    ) {
        return reconcile(
                settlementId,
                userId,
                expectedSnapshot,
                null,
                fetchedTransactions,
                now,
                false
        );
    }

    @Transactional
    public SettlementDepositSummaryResult reconcileForSuspension(
            UUID settlementId,
            Long userId,
            SettlementReceivingAccountSnapshot expectedSnapshot,
            SettlementStatus expectedStatus,
            List<Transaction> fetchedTransactions,
            LocalDateTime now
    ) {
        return reconcile(
                settlementId,
                userId,
                expectedSnapshot,
                expectedStatus,
                fetchedTransactions,
                now,
                true
        );
    }

    private SettlementDepositSummaryResult reconcile(
            UUID settlementId,
            Long userId,
            SettlementReceivingAccountSnapshot expectedSnapshot,
            SettlementStatus expectedStatus,
            List<Transaction> fetchedTransactions,
            LocalDateTime now,
            boolean allowRequested
    ) {
        var lockedAccount = loadAccountPort.findActiveByIdAndUserIdForUpdate(
                expectedSnapshot.sourceAccountId(),
                userId
        );
        if (allowRequested && lockedAccount.isEmpty()) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        verifyOwnership(settlement, userId);
        if (expectedStatus != null && settlement.status() != expectedStatus) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
        ensureAvailable(settlement.status(), allowRequested);

        var data = reader.load(settlement, now);
        if (!data.snapshot().equals(expectedSnapshot)) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
        if (settlement.status() == SettlementStatus.COMPLETED) {
            return reader.summarize(
                    settlement,
                    data.payableShares(),
                    data.confirmedAmounts()
            );
        }

        saveTransactionPort.saveAllIgnoringDuplicates(fetchedTransactions);
        List<Transaction> transactions = loadTransactionPort.findIncomeByAccountIdBetween(
                data.snapshot().sourceAccountId(),
                data.snapshot().createdAt(),
                now
        );
        Map<Long, Transaction> transactionById = transactions.stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        List<SettlementDeposit> existingDeposits =
                depositStorePort.findAllBySettlementId(settlement.id());
        Map<UUID, Long> confirmedAmounts = confirmedAmounts(existingDeposits, transactionById);
        Map<UUID, Long> previousAmounts = new HashMap<>(confirmedAmounts);
        Map<UUID, SettlementDepositReader.PayableShare> currentPayableByShareId =
                data.payableShares().stream()
                        .collect(Collectors.toMap(
                                payable -> payable.share().id(),
                                Function.identity()
                        ));
        List<SettlementDepositStorePort.PayableDepositCandidate> accountCandidates =
                depositStorePort.findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                        settlement.id(),
                        userId,
                        data.snapshot().sourceAccountId(),
                        data.snapshot().organization(),
                        data.snapshot().accountNumber()
                );
        Set<String> matchedTransactionKeys = new HashSet<>(
                depositStorePort.findMatchedExternalTransactionKeysByReceivingAccount(
                        userId,
                        data.snapshot().sourceAccountId(),
                        data.snapshot().organization(),
                        data.snapshot().accountNumber(),
                        transactions.stream()
                                .map(Transaction::externalTransactionKey)
                                .collect(Collectors.toSet())
                )
        );
        List<SettlementDeposit> newDeposits = new ArrayList<>();

        for (Transaction transaction : transactions) {
            if (matchedTransactionKeys.contains(transaction.externalTransactionKey())) {
                continue;
            }
            List<SettlementDepositMatchingPolicy.PayableShare> candidates = accountCandidates
                    .stream()
                    .map(candidate -> toMatchingCandidate(
                            candidate,
                            currentPayableByShareId.get(candidate.shareId()),
                            confirmedAmounts
                    ))
                    .filter(candidate -> candidate.remainingAmount() > 0)
                    .toList();
            matchingPolicy.match(transaction, candidates)
                    .filter(currentPayableByShareId::containsKey)
                    .ifPresent(shareId -> {
                        SettlementDeposit deposit = SettlementDeposit.create(
                                settlement.id(),
                                shareId,
                                data.snapshot().sourceAccountId(),
                                transaction.id(),
                                now
                        );
                        newDeposits.add(deposit);
                        matchedTransactionKeys.add(transaction.externalTransactionKey());
                        confirmedAmounts.merge(
                                shareId,
                                transaction.amount().longValueExact(),
                                Math::addExact
                        );
                    });
        }
        depositStorePort.saveAll(newDeposits);
        for (var payable : data.payableShares()) {
            long expected = payable.share().amount();
            UUID shareId = payable.share().id();
            if (previousAmounts.getOrDefault(shareId, 0L) < expected
                    && confirmedAmounts.getOrDefault(shareId, 0L) >= expected) {
                notifications.settlementPaymentCompleted(settlement, shareId, payable.payerName(), now);
            }
        }

        Settlement result = settlement.status() == SettlementStatus.AWAITING_DEPOSIT
                && allCompleted(data.payableShares(), confirmedAmounts)
                ? saveSettlementPort.update(settlement.complete(now))
                : settlement;
        if (result.status() == SettlementStatus.COMPLETED) notifications.settlementCompleted(result, now);
        return reader.summarize(result, data.payableShares(), confirmedAmounts);
    }

    private SettlementDepositMatchingPolicy.PayableShare toMatchingCandidate(
            SettlementDepositStorePort.PayableDepositCandidate candidate,
            SettlementDepositReader.PayableShare currentPayable,
            Map<UUID, Long> confirmedAmounts
    ) {
        long remainingAmount = currentPayable == null
                ? candidate.remainingAmount()
                : Math.max(0L, currentPayable.share().amount()
                        - confirmedAmounts.getOrDefault(candidate.shareId(), 0L));
        return new SettlementDepositMatchingPolicy.PayableShare(
                candidate.shareId(),
                candidate.payerName(),
                remainingAmount,
                candidate.snapshotCreatedAt()
        );
    }

    private Map<UUID, Long> confirmedAmounts(
            List<SettlementDeposit> deposits,
            Map<Long, Transaction> transactionById
    ) {
        return deposits.stream().collect(Collectors.toMap(
                SettlementDeposit::shareId,
                deposit -> transactionAmount(deposit, transactionById),
                Math::addExact
        ));
    }

    private long transactionAmount(
            SettlementDeposit deposit,
            Map<Long, Transaction> transactionById
    ) {
        Transaction transaction = transactionById.get(deposit.accountTransactionId());
        if (transaction == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
            );
        }
        try {
            return transaction.amount().longValueExact();
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND,
                    exception
            );
        }
    }

    private boolean allCompleted(
            List<SettlementDepositReader.PayableShare> payableShares,
            Map<UUID, Long> confirmedAmounts
    ) {
        return payableShares.stream().allMatch(payable ->
                confirmedAmounts.getOrDefault(payable.share().id(), 0L)
                        == payable.share().amount()
        );
    }

    private void verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
    }

    private void ensureAvailable(SettlementStatus status, boolean allowRequested) {
        if (status != SettlementStatus.AWAITING_DEPOSIT
                && status != SettlementStatus.COMPLETED
                && !(allowRequested && status == SettlementStatus.REQUESTED)) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE
            );
        }
    }
}
