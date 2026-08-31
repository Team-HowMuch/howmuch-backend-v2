package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
class SettlementDepositReader {
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SettlementDepositStorePort depositStorePort;

    @Transactional(readOnly = true)
    public SettlementDepositSummaryResult read(
            UUID settlementId,
            Long userId,
            LocalDateTime now
    ) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        verifyOwnership(settlement, userId);
        ensureAvailable(settlement.status());
        DepositData data = load(settlement, now);
        return summarize(settlement, data.payableShares(), data.confirmedAmounts());
    }

    DepositData load(Settlement settlement, LocalDateTime now) {
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

    SettlementDepositSummaryResult summarize(
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
                    return new PayableShare(
                            share,
                            participant.displayName(),
                            participant.linkedUserId()
                    );
                })
                .filter(payable -> !settlement.creatorId().equals(payable.linkedUserId()))
                .toList();
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
            try {
                long amount = transaction.amount().longValueExact();
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

    private void verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
    }

    private void ensureAvailable(SettlementStatus status) {
        if (status != SettlementStatus.AWAITING_DEPOSIT
                && status != SettlementStatus.COMPLETED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE
            );
        }
    }

    record PayableShare(SettlementShare share, String payerName, Long linkedUserId) {
    }

    record DepositData(
            SettlementReceivingAccountSnapshot snapshot,
            List<PayableShare> payableShares,
            Map<UUID, Long> confirmedAmounts
    ) {
    }
}
