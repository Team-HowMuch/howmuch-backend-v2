package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettedDepositMatchingPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDepositMatchingPolicy;
import com.amateur.howmuchbackend.domain.account.Transaction;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettedDepositCommitWriter {
    private final LoadGroupNettingPort nettings;
    private final SaveGroupNettingPort saveNettings;
    private final LoadGroupNettingDebtPort locks;
    private final LoadGroupPort groups;
    private final LoadAccountTransactionPort transactions;
    private final NettingDepositStorePort deposits;
    private final NettingExecutionStorePort executions;
    private final LoadSettlementPort settlements;
    private final SaveSettlementPort saveSettlements;
    private final NettingNotificationService notifications;
    private final NettedDepositMatchingPolicy matching;
    private final SettlementDepositMatchingPolicy originalMatching;
    private final NettingEstimateInvalidationService estimateInvalidation;

    @Transactional
    public void reconcile(NettingExecutionStorePort.Execution execution, LocalDateTime now) {
        apply(execution, execution.nettingId(), false, now);
    }

    @Transactional
    public void refresh(NettingExecutionStorePort.Execution execution, UUID targetId) {
        apply(execution, targetId, true, LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    private void apply(
            NettingExecutionStorePort.Execution execution,
            UUID targetId,
            boolean allOwners,
            LocalDateTime now) {
        var initial = nettings.find(execution.groupId(), targetId).orElseThrow();
        locks.lockPaymentInputs(initial);
        groups.findByIdForUpdate(execution.groupId()).orElseThrow();
        saveNettings.lockNetting(initial.id());
        now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        executions.requireLease(execution.id(), execution.leaseToken(), now);
        var n = nettings.find(execution.groupId(), targetId).orElseThrow();
        boolean changed = false;
        for (var account :
                allOwners
                        ? nettings.monitoredAccounts(n.id())
                        : nettings.monitoredAccounts(n.id(), execution.actorId())) {
            var income =
                    transactions.findIncomeByAccountIdBetween(
                            account.accountId(), account.activatedAt(), now);
            for (Transaction transaction : income) {
                if (deposits.isReviewed(n.id(), transaction.id())) continue;
                if (deposits.alreadyCredited(
                        n.id(), account.organization(), transaction.externalTransactionKey()))
                    continue;
                var current = nettings.find(execution.groupId(), n.id()).orElseThrow();
                var candidates =
                        current.transfers().stream()
                                .filter(t -> t.receiverId() == account.userId())
                                .toList();
                if (!"ACTIVE".equals(current.status())
                        && !matching.hasKnownReference(transaction, current.transfers())
                        && !resemblesOriginalPayment(
                                current.id(), account.userId(), account.accountId(), transaction))
                    continue;
                var target =
                        "ACTIVE".equals(current.status())
                                ? matching.match(transaction, candidates)
                                : Optional.<UUID>empty();
                if (target.isPresent()) {
                    var transfer =
                            candidates.stream()
                                    .filter(t -> t.id().equals(target.get()))
                                    .findFirst()
                                    .orElseThrow();
                    var credit = deposits.credit(transfer, transaction, now);
                    if (credit == NettingDepositStorePort.Credit.APPLIED) changed = true;
                    if (credit != NettingDepositStorePort.Credit.CONFLICT) continue;
                }
                deposits.openReview(
                        n.id(),
                        account.userId(),
                        account.organization(),
                        transaction,
                        "ACTIVE".equals(current.status())
                                ? "UNMATCHED_OR_CONFLICT"
                                : "AFTER_COMPLETION",
                        now);
                changed = true;
            }
        }
        if (complete(n, now)) changed = true;
        if (changed) estimateInvalidation.groupChanged(n.groupId());
        now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        executions.requireLease(execution.id(), execution.leaseToken(), now);
        if (!allOwners)
            executions.finish(
                    execution.id(),
                    execution.leaseToken(),
                    NettingExecutionStorePort.Status.SUCCEEDED,
                    n.id(),
                    null,
                    now);
    }

    private boolean resemblesOriginalPayment(
            UUID id, long receiver, long account, Transaction transaction) {
        // Reuse original name/amount evidence to request review only; never auto-credit a completed
        // netting.
        return nettings.originalPaymentCandidates(id, receiver, account).stream()
                .anyMatch(
                        candidate ->
                                originalMatching
                                        .match(
                                                transaction,
                                                List.of(
                                                        new SettlementDepositMatchingPolicy
                                                                .PayableShare(
                                                                candidate.shareId(),
                                                                candidate.payerName(),
                                                                candidate.remainingAmount(),
                                                                candidate.createdAt())))
                                        .isPresent());
    }

    public boolean complete(LoadGroupNettingPort.Netting n, LocalDateTime now) {
        var fresh = nettings.findById(n.id()).orElseThrow();
        boolean paid = fresh.transfers().stream().allMatch(t -> t.confirmedAmount() == t.amount());
        if (fresh.aggregate().completeIfPaid(paid, fresh.openReviews() > 0).isPresent()
                && saveNettings.completeIfPaid(n.id(), now)) {
            for (var source : n.sources()) {
                var settlement = settlements.findById(source.settlementId()).orElseThrow();
                saveSettlements.update(settlement.completeByNetting(now));
            }
            notifications.completed(n.id(), n.groupId(), now);
            return true;
        }
        return false;
    }
}
