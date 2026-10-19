package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.service.NettingBankContextReader;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettingBankQueryGuard {
    private final NettingBankContextReader contexts;
    private final NettingExecutionStorePort executions;
    private final GroupAccessService groups;
    private final LoadGroupNettingDebtPort sources;
    private final LoadGroupNettingPort nettings;
    private final GroupNettingAccessService access;

    @Transactional
    public ExternalConnection source(
            NettingExecutionStorePort.Execution e,
            NettingInput.Source source,
            com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot
                    snapshot) {
        groups.loadActive(e.groupId());
        groups.requireActiveMember(e.groupId(), e.actorId());
        if (sources.findAwaitingSourceIdentity(e.groupId(), source.settlementId())
                .filter(
                        s ->
                                s.settlementId().equals(source.settlementId())
                                        && s.receivingAccountId() == snapshot.sourceAccountId()
                                        && s.receivingAccountDigest()
                                                .equals(source.receivingAccountDigest()))
                .isEmpty()) throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        executions.requireLease(e.id(), e.leaseToken(), LocalDateTime.now(ZoneOffset.UTC));
        return contexts.load(
                snapshot.sourceAccountId(), source.creatorId(), snapshot.accountNumber());
    }

    @Transactional
    public ExternalConnection monitored(
            NettingExecutionStorePort.Execution e,
            UUID nettingId,
            LoadGroupNettingPort.ReceivingAccount account) {
        if (e.kind() == NettingExecutionStorePort.Kind.CREATE) {
            groups.loadActive(e.groupId());
            groups.requireActiveMember(e.groupId(), e.actorId());
            if (nettings.findActive(e.groupId()).filter(n -> n.id().equals(nettingId)).isEmpty())
                throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        } else {
            access.requireAccess(e.groupId(), nettingId, e.actorId());
            if (account.userId() != e.actorId())
                throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        }
        if (!nettings.monitoredAccounts(nettingId, account.userId()).contains(account))
            throw ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED);
        executions.requireLease(e.id(), e.leaseToken(), LocalDateTime.now(ZoneOffset.UTC));
        return contexts.load(account.accountId(), account.userId(), account.accountNumber());
    }
}
