package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.ProcessNettingExecutionUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.service.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class GroupNettingWorkerService implements ProcessNettingExecutionUseCase {
    private final NettingExecutionStorePort executions;
    private final LoadSettlementReceivingAccountSnapshotPort snapshots;
    private final LoadGroupNettingPort nettings;
    private final GroupNettingInputReader inputs;
    private final NettingBankQueryGuard queryGuard;
    private final SettlementBankTransactionFetchService fetcher;
    private final NettingOriginalDepositWriter originalWriter;
    private final NettingObservedTransactionWriter observedWriter;
    private final GroupNettingCommitWriter commitWriter;
    private final NettedDepositCommitWriter depositWriter;

    public com.amateur.howmuchbackend.application.port.in.settlement.netting.result
                    .NettingSynchronizationResult
            synchronize(UUID id, UUID token) {
        return new com.amateur.howmuchbackend.application.port.in.settlement.netting.result
                .NettingSynchronizationResult(synchronizeInput(id, token));
    }

    private NettingInput synchronizeInput(UUID id, UUID token) {
        var e = load(id, token);
        if (e.kind() == NettingExecutionStorePort.Kind.DETECT) return null;
        if (e.kind() == NettingExecutionStorePort.Kind.CREATE) {
            var before = inputs.read(e.groupId());
            var fetchedByAccount =
                    new HashMap<
                            Long, List<com.amateur.howmuchbackend.domain.account.Transaction>>();
            var sourceSnapshots =
                    new LinkedHashMap<
                            UUID,
                            com.amateur.howmuchbackend.domain.settlement
                                    .SettlementReceivingAccountSnapshot>();
            var earliest = new HashMap<Long, LocalDateTime>();
            for (var source : before.sources()) {
                var snap = snapshots.findBySettlementId(source.settlementId()).orElseThrow();
                sourceSnapshots.put(source.settlementId(), snap);
                earliest.merge(
                        snap.sourceAccountId(), snap.createdAt(), (a, b) -> a.isBefore(b) ? a : b);
            }
            if (before.predecessorNettingId() != null)
                for (var account : nettings.monitoredAccounts(before.predecessorNettingId()))
                    earliest.merge(
                            account.accountId(),
                            account.activatedAt(),
                            (a, b) -> a.isBefore(b) ? a : b);
            for (var source : before.sources()) {
                var snap = sourceSnapshots.get(source.settlementId());
                var context = queryGuard.source(e, source, snap);
                var fetched =
                        fetchedByAccount.computeIfAbsent(
                                snap.sourceAccountId(),
                                ignored ->
                                        fetcher.fetch(
                                                context,
                                                snap.sourceAccountId(),
                                                snap.organization(),
                                                snap.accountNumber(),
                                                earliest.get(snap.sourceAccountId()),
                                                LocalDateTime.now(ZoneOffset.UTC),
                                                () -> queryGuard.source(e, source, snap)));
                originalWriter.apply(e, source, snap, fetched, LocalDateTime.now(ZoneOffset.UTC));
            }
            if (before.predecessorNettingId() != null) {
                for (var account : nettings.monitoredAccounts(before.predecessorNettingId())) {
                    var context = queryGuard.monitored(e, before.predecessorNettingId(), account);
                    var fetched =
                            fetchedByAccount.computeIfAbsent(
                                    account.accountId(),
                                    ignored ->
                                            fetcher.fetch(
                                                    context,
                                                    account.accountId(),
                                                    account.organization(),
                                                    account.accountNumber(),
                                                    earliest.get(account.accountId()),
                                                    LocalDateTime.now(ZoneOffset.UTC),
                                                    () ->
                                                            queryGuard.monitored(
                                                                    e,
                                                                    before.predecessorNettingId(),
                                                                    account)));
                    observedWriter.save(e, account, fetched, LocalDateTime.now(ZoneOffset.UTC));
                }
                depositWriter.refresh(e, before.predecessorNettingId());
            }
            var fresh = inputs.read(e.groupId());
            // A provider response for the old account identity cannot synchronize its replacement.
            if (fresh.predecessorNettingId() != null
                    && (!Objects.equals(before.predecessorNettingId(), fresh.predecessorNettingId())
                            || !before.monitoringAccounts().equals(fresh.monitoringAccounts())))
                throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
            if (fresh.sources().stream()
                    .anyMatch(
                            source ->
                                    before.sources().stream()
                                            .noneMatch(
                                                    old ->
                                                            old.settlementId()
                                                                            .equals(
                                                                                    source
                                                                                            .settlementId())
                                                                    && old.receivingAccountId()
                                                                            == source
                                                                                    .receivingAccountId()
                                                                    && old.receivingAccountDigest()
                                                                            .equals(
                                                                                    source
                                                                                            .receivingAccountDigest()))))
                throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
            return fresh;
        }
        for (var account : nettings.monitoredAccounts(e.nettingId(), e.actorId())) {
            var context = queryGuard.monitored(e, e.nettingId(), account);
            var fetched =
                    fetcher.fetch(
                            context,
                            account.accountId(),
                            account.organization(),
                            account.accountNumber(),
                            account.activatedAt(),
                            LocalDateTime.now(ZoneOffset.UTC),
                            () -> queryGuard.monitored(e, e.nettingId(), account));
            observedWriter.save(e, account, fetched, LocalDateTime.now(ZoneOffset.UTC));
        }
        return null;
    }

    public void commit(
            com.amateur.howmuchbackend.application.port.in.settlement.netting.command
                            .CommitNettingExecutionCommand
                    command) {
        UUID id = command.executionId(), token = command.leaseToken();
        var input = command.snapshot();
        var e = load(id, token);
        if (e.kind() == NettingExecutionStorePort.Kind.CREATE) commitWriter.create(e, input);
        else depositWriter.reconcile(e, LocalDateTime.now(ZoneOffset.UTC));
    }

    public void failed(UUID id, UUID token, String code) {
        var status =
                Set.of(
                                        "NETTING_INPUT_CHANGED",
                                        "NETTING_NO_ELIGIBLE_DEBTS",
                                        "NETTING_INSUFFICIENT_SETTLEMENTS",
                                        "NETTING_INVALID_SOURCE",
                                        "NETTING_ALREADY_ACTIVE")
                                .contains(code)
                        ? NettingExecutionStorePort.Status.STALE
                        : code.equals("NETTING_WORKER_FAILED")
                                ? NettingExecutionStorePort.Status.RETRY
                                : NettingExecutionStorePort.Status.FAILED;
        executions.finish(id, token, status, null, code, LocalDateTime.now(ZoneOffset.UTC));
    }

    private NettingExecutionStorePort.Execution load(UUID id, UUID token) {
        var e =
                executions
                        .findById(id)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        if (!Objects.equals(e.leaseToken(), token)
                || e.status() != NettingExecutionStorePort.Status.SYNCING
                || !e.deadline().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            throw ApplicationException.of(NettingStatusCode.NETTING_EXECUTION_FENCED);
        return e;
    }
}
