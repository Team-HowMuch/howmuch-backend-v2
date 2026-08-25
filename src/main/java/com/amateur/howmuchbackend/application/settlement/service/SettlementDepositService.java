package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDepositSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReconcileSettlementDepositsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDepositMatchingPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementDepositService implements
        ReconcileSettlementDepositsUseCase,
        GetSettlementDepositSummaryUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    private final SettlementDepositStorePort depositStorePort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SaveAccountTransactionPort saveTransactionPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final BankTransactionPort bankTransactionPort;
    private final SettlementDepositMatchingPolicy matchingPolicy;

    @Override
    @Transactional
    public SettlementDepositSummaryResult reconcile(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        ensureAvailable(settlement.status());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DepositData data = loadData(settlement, now);
        if (settlement.status() == SettlementStatus.COMPLETED) {
            return summarize(settlement, data.payableShares(), data.confirmedAmounts());
        }

        ExternalConnection connection = connectionStorePort.findActiveByUserId(actor.userId())
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        saveTransactionPort.saveAllIgnoringDuplicates(fetchTransactions(
                connection,
                data.snapshot(),
                now
        ));

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
        Set<Long> matchedTransactionIds = new HashSet<>(depositStorePort.findMatchedTransactionIds(
                transactionById.keySet()
        ));
        Map<UUID, PayableShare> currentPayableByShareId = data.payableShares().stream()
                .collect(Collectors.toMap(payable -> payable.share().id(), Function.identity()));
        List<SettlementDepositStorePort.PayableDepositCandidate> accountCandidates =
                depositStorePort.findUnfinishedPayableCandidatesBySourceAccountId(
                        data.snapshot().sourceAccountId()
                );
        List<SettlementDeposit> newDeposits = new ArrayList<>();

        for (Transaction transaction : transactions) {
            if (matchedTransactionIds.contains(transaction.id())) {
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
                        matchedTransactionIds.add(transaction.id());
                        confirmedAmounts.merge(
                                shareId,
                                transaction.amount().longValueExact(),
                                Math::addExact
                        );
                    });
        }
        depositStorePort.saveAll(newDeposits);

        Settlement result = allCompleted(data.payableShares(), confirmedAmounts)
                ? saveSettlementPort.update(settlement.complete(now))
                : settlement;
        return summarize(result, data.payableShares(), confirmedAmounts);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementDepositSummaryResult get(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        ensureAvailable(settlement.status());
        DepositData data = loadData(settlement, LocalDateTime.now(ZoneOffset.UTC));
        return summarize(settlement, data.payableShares(), data.confirmedAmounts());
    }

    private DepositData loadData(Settlement settlement, LocalDateTime now) {
        SettlementReceivingAccountSnapshot snapshot = loadSnapshotPort
                .findBySettlementId(settlement.id())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                ));
        List<PayableShare> payableShares = payableShares(
                settlement,
                loadSettlementSharePort.findAllBySettlementId(settlement.id()),
                loadSettlementParticipantPort.findAllBySettlementId(settlement.id())
        );
        List<Transaction> transactions = loadTransactionPort.findIncomeByAccountIdBetween(
                snapshot.sourceAccountId(),
                snapshot.createdAt(),
                now
        );
        Map<Long, Transaction> transactionById = transactions.stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        Map<UUID, Long> confirmedAmounts = confirmedAmounts(
                depositStorePort.findAllBySettlementId(settlement.id()),
                transactionById
        );
        return new DepositData(snapshot, payableShares, confirmedAmounts);
    }

    private List<Transaction> fetchTransactions(
            ExternalConnection connection,
            SettlementReceivingAccountSnapshot snapshot,
            LocalDateTime now
    ) {
        LocalDate startDate = seoulDate(snapshot.createdAt());
        LocalDate endDate = seoulDate(now);
        if (startDate.isAfter(endDate)) {
            return List.of();
        }

        List<Transaction> result = new ArrayList<>();
        YearMonth month = YearMonth.from(startDate);
        YearMonth lastMonth = YearMonth.from(endDate);
        while (!month.isAfter(lastMonth)) {
            LocalDate periodStart = month.equals(YearMonth.from(startDate))
                    ? startDate : month.atDay(1);
            LocalDate periodEnd = month.equals(lastMonth)
                    ? endDate : month.atEndOfMonth();
            bankTransactionPort.fetch(
                    connection.connectedId(),
                    snapshot.organization(),
                    snapshot.accountNumber(),
                    periodStart,
                    periodEnd
            ).stream()
                    .map(transaction -> new Transaction(
                            null,
                            snapshot.sourceAccountId(),
                            AccountTransactionKey.from(snapshot.accountNumber(), transaction),
                            transaction.type(),
                            transaction.amount(),
                            transaction.transactedAt(),
                            transaction.description(),
                            transaction.afterBalance()
                    ))
                    .forEach(result::add);
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }

    private List<PayableShare> payableShares(
            Settlement settlement,
            List<SettlementShare> shares,
            List<SettlementParticipant> participants
    ) {
        Map<UUID, SettlementParticipant> participantById = participants.stream()
                .collect(Collectors.toMap(SettlementParticipant::id, Function.identity()));
        return shares.stream()
                .map(share -> {
                    SettlementParticipant participant = participantById.get(share.payerParticipantId());
                    if (participant == null) {
                        throw ApplicationException.of(
                                SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                        );
                    }
                    return new PayableShare(share, participant.displayName(), participant.linkedUserId());
                })
                .filter(payable -> !settlement.creatorId().equals(payable.linkedUserId()))
                .toList();
    }

    private SettlementDepositMatchingPolicy.PayableShare toMatchingCandidate(
            SettlementDepositStorePort.PayableDepositCandidate candidate,
            PayableShare currentPayable,
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
        Map<UUID, Long> result = new HashMap<>();
        for (SettlementDeposit deposit : deposits) {
            Transaction transaction = transactionById.get(deposit.accountTransactionId());
            if (transaction == null) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                );
            }
            long amount;
            try {
                amount = transaction.amount().longValueExact();
                result.merge(deposit.shareId(), amount, Math::addExact);
            } catch (ArithmeticException exception) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND,
                        exception
                );
            }
        }
        return result;
    }

    private boolean allCompleted(
            List<PayableShare> payableShares,
            Map<UUID, Long> confirmedAmounts
    ) {
        return payableShares.stream().allMatch(payable ->
                confirmedAmounts.getOrDefault(payable.share().id(), 0L)
                        == payable.share().amount()
        );
    }

    private SettlementDepositSummaryResult summarize(
            Settlement settlement,
            List<PayableShare> payableShares,
            Map<UUID, Long> confirmedAmounts
    ) {
        long expectedAmount = payableShares.stream()
                .mapToLong(payable -> payable.share().amount())
                .sum();
        long confirmedAmount = payableShares.stream()
                .mapToLong(payable -> confirmedAmounts.getOrDefault(payable.share().id(), 0L))
                .sum();
        int completedCount = Math.toIntExact(payableShares.stream()
                .filter(payable -> confirmedAmounts.getOrDefault(payable.share().id(), 0L)
                        == payable.share().amount())
                .count());
        return new SettlementDepositSummaryResult(
                settlement.id(),
                settlement.status(),
                expectedAmount,
                confirmedAmount,
                payableShares.size(),
                completedCount
        );
    }

    private Settlement loadOwnedSettlement(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        return verifyOwnership(settlement, userId);
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        return verifyOwnership(settlement, userId);
    }

    private Settlement verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private void ensureAvailable(SettlementStatus status) {
        if (status != SettlementStatus.AWAITING_DEPOSIT
                && status != SettlementStatus.COMPLETED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE
            );
        }
    }

    private LocalDate seoulDate(LocalDateTime utcDateTime) {
        return utcDateTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(SEOUL)
                .toLocalDate();
    }

    private record PayableShare(
            SettlementShare share,
            String payerName,
            Long linkedUserId
    ) {
    }

    private record DepositData(
            SettlementReceivingAccountSnapshot snapshot,
            List<PayableShare> payableShares,
            Map<UUID, Long> confirmedAmounts
    ) {
    }
}
