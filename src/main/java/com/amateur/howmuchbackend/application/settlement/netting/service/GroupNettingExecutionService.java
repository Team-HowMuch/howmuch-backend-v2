package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.command.ExecuteGroupNettingCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingExecutionResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class GroupNettingExecutionService
        implements ExecuteGroupNettingUseCase, ReconcileNettedTransfersUseCase {
    private final CurrentActorPort actor;
    private final GroupAccessService groups;
    private final NettingExecutionStorePort executions;
    private final GroupNettingAccessService access;
    private final LoadGroupNettingPort nettings;
    private final GroupNettingInputReader inputs;

    @Transactional
    public NettingExecutionResult execute(long groupId, ExecuteGroupNettingCommand command) {
        long user = actor.getCurrentActor().userId();
        groups.loadActiveForUpdate(groupId);
        groups.requireActiveMember(groupId, user);
        var old =
                executions.findByRequest(
                        groupId,
                        user,
                        NettingExecutionStorePort.Kind.CREATE,
                        command.idempotencyKey());
        if (old.isPresent()) return result(old.get());
        inputs.requireCompressibleScope(groupId);
        return result(
                executions.enqueue(
                        groupId,
                        null,
                        user,
                        NettingExecutionStorePort.Kind.CREATE,
                        command.idempotencyKey(),
                        LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional
    public NettingExecutionResult reconcile(long groupId, UUID nettingId, UUID key) {
        long user = actor.getCurrentActor().userId();
        access.requireAccess(groupId, nettingId, user);
        if (nettings.monitoredAccounts(nettingId, user).isEmpty())
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        var old =
                executions.findByRequest(
                        groupId, user, NettingExecutionStorePort.Kind.RECONCILE, key);
        if (old.isPresent() && !Objects.equals(old.get().nettingId(), nettingId))
            throw ApplicationException.of(NettingStatusCode.NETTING_IDEMPOTENCY_CONFLICT);
        return result(
                old.orElseGet(
                        () ->
                                executions.enqueue(
                                        groupId,
                                        nettingId,
                                        user,
                                        NettingExecutionStorePort.Kind.RECONCILE,
                                        key,
                                        LocalDateTime.now(ZoneOffset.UTC))));
    }

    @Transactional(readOnly = true)
    public NettingExecutionResult getExecution(long groupId, UUID id) {
        long user = actor.getCurrentActor().userId();
        var execution =
                executions
                        .find(groupId, id)
                        .orElseThrow(
                                () -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
        if (execution.actorId() != user
                || execution.kind() == NettingExecutionStorePort.Kind.DETECT)
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        groups.requireActiveMember(groupId, user);
        return result(execution);
    }

    private NettingExecutionResult result(NettingExecutionStorePort.Execution e) {
        return new NettingExecutionResult(e.id(), e.nettingId(), e.status().name(), e.errorCode());
    }
}
