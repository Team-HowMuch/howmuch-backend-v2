package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class GroupNettingCommitWriter {
    private final NettingExecutionStorePort executions;
    private final LoadGroupNettingDebtPort debts;
    private final GroupNettingInputReader inputs;
    private final GroupAccessService groups;
    private final LoadAccountPort accounts;
    private final LoadSettlementPort settlements;
    private final SaveSettlementPort saveSettlements;
    private final SaveGroupNettingPort nettings;
    private final LoadGroupNettingPort loadNettings;
    private final NettingNotificationService notifications;
    private final NettingEstimateInvalidationService estimateInvalidation;

    @Transactional
    public void create(NettingExecutionStorePort.Execution e, NettingInput snapshot) {
        if (snapshot == null || snapshot.groupId() != e.groupId())
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        debts.lockInputs(snapshot);
        groups.loadActiveForUpdate(e.groupId());
        groups.requireActiveMember(e.groupId(), e.actorId());
        if (snapshot.predecessorNettingId() != null)
            nettings.lockNetting(snapshot.predecessorNettingId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        executions.requireLease(e.id(), e.leaseToken(), now);
        var fresh = inputs.recalculate(e.groupId(), snapshot.calculatorSelection());
        if (!GroupNettingInputReader.fingerprint(snapshot)
                .equals(GroupNettingInputReader.fingerprint(fresh)))
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        var receiving =
                fresh.receivers().stream()
                        .map(
                                r ->
                                        accounts.findActiveByIdAndUserId(r.accountId(), r.userId())
                                                .orElseThrow())
                        .toList();
        UUID id = UUID.randomUUID();
        nettings.create(
                GroupSettlementNetting.create(id, e.groupId(), fresh.version(), now),
                e.id(),
                fresh,
                receiving,
                now);
        for (var source : fresh.sources())
            saveSettlements.update(
                    settlements.findById(source.settlementId()).orElseThrow().enterNetting(now));
        notifications.confirmed(id, e.groupId(), now);
        if (fresh.calculation().payments().isEmpty() && nettings.completeIfPaid(id, now)) {
            for (var source : loadNettings.findById(id).orElseThrow().sources()) {
                var original = settlements.findById(source.settlementId()).orElseThrow();
                if (original.status() == SettlementStatus.NETTING_IN_PROGRESS)
                    saveSettlements.update(original.completeByNetting(now));
            }
            notifications.completed(id, e.groupId(), now);
        }
        var finished = LocalDateTime.now(ZoneOffset.UTC);
        executions.requireLease(e.id(), e.leaseToken(), finished);
        executions.finish(
                e.id(),
                e.leaseToken(),
                NettingExecutionStorePort.Status.SUCCEEDED,
                id,
                null,
                finished);
        estimateInvalidation.groupChanged(e.groupId());
    }
}
