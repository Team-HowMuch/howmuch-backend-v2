package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.ProcessNettingExecutionUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingExecutionFailurePolicy;
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
    private final NettingBankTransactionReader bankReader;
    private final NettingOriginalDepositWriter originalWriter;
    private final NettingObservedTransactionWriter observedWriter;
    private final GroupNettingCommitWriter commitWriter;
    private final NettedDepositCommitWriter depositWriter;
    private final NettingExecutionFailurePolicy failures;

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
            var monitored =
                    before.predecessorNettingId() == null
                            ? List.<LoadGroupNettingPort.ReceivingAccount>of()
                            : nettings.monitoredAccounts(before.predecessorNettingId());
            for (var account : monitored) {
                earliest.merge(
                        account.accountId(),
                        account.activatedAt(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
            // Keep first-use order for error handling, and deduplicate source/predecessor accounts.
            Map<Long, NettingBankTransactionReader.Query> queries = new LinkedHashMap<>();
            for (var source : before.sources()) {
                var snap = sourceSnapshots.get(source.settlementId());
                queries.putIfAbsent(
                        snap.sourceAccountId(),
                        new NettingBankTransactionReader.Query(
                                snap.sourceAccountId(),
                                snap.organization(),
                                snap.accountNumber(),
                                earliest.get(snap.sourceAccountId()),
                                () -> queryGuard.source(e, source, snap)));
            }
            for (var account : monitored) {
                queries.putIfAbsent(
                        account.accountId(),
                        new NettingBankTransactionReader.Query(
                                account.accountId(),
                                account.organization(),
                                account.accountNumber(),
                                earliest.get(account.accountId()),
                                () ->
                                        queryGuard.monitored(
                                                e, before.predecessorNettingId(), account)));
            }
            var fetchedByAccount = bankReader.fetch(List.copyOf(queries.values()));
            Set<Long> appliedAccounts = new HashSet<>();
            for (var source : before.sources()) {
                var snap = sourceSnapshots.get(source.settlementId());
                // First-use and per-period guards ran in the fetch task. Reused results retain
                // the original per-source guard before the existing transactional writer.
                if (!appliedAccounts.add(snap.sourceAccountId()))
                    queryGuard.source(e, source, snap);
                var fetched = fetchedByAccount.get(snap.sourceAccountId()).transactionsOrThrow();
                originalWriter.apply(e, source, snap, fetched, LocalDateTime.now(ZoneOffset.UTC));
            }
            for (var account : monitored) {
                if (!appliedAccounts.add(account.accountId())) {
                    queryGuard.monitored(e, before.predecessorNettingId(), account);
                }
                var fetched = fetchedByAccount.get(account.accountId()).transactionsOrThrow();
                observedWriter.save(e, account, fetched, LocalDateTime.now(ZoneOffset.UTC));
            }
            if (before.predecessorNettingId() != null) {
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
        var monitored = nettings.monitoredAccounts(e.nettingId(), e.actorId());
        Map<Long, NettingBankTransactionReader.Query> queries = new LinkedHashMap<>();
        for (var account : monitored) {
            queries.putIfAbsent(
                    account.accountId(),
                    new NettingBankTransactionReader.Query(
                            account.accountId(),
                            account.organization(),
                            account.accountNumber(),
                            account.activatedAt(),
                            () -> queryGuard.monitored(e, e.nettingId(), account)));
        }
        var fetchedByAccount = bankReader.fetch(List.copyOf(queries.values()));
        for (var account : monitored) {
            observedWriter.save(
                    e,
                    account,
                    fetchedByAccount.get(account.accountId()).transactionsOrThrow(),
                    LocalDateTime.now(ZoneOffset.UTC));
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
        executions.finish(
                id, token, failures.statusFor(code), null, code, LocalDateTime.now(ZoneOffset.UTC));
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
