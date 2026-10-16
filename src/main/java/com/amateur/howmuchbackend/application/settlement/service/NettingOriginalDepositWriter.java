package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class NettingOriginalDepositWriter {
    private final LoadAccountPort accounts;
    private final LoadSettlementPort settlements;
    private final GroupAccessService groups;
    private final NettingExecutionStorePort executions;
    private final LoadGroupNettingDebtPort sources;
    private final SettlementDepositWriter writer;

    @Transactional
    public void apply(
            NettingExecutionStorePort.Execution e,
            NettingInput.Source source,
            SettlementReceivingAccountSnapshot snapshot,
            List<Transaction> transactions,
            LocalDateTime now) {
        accounts.findActiveByIdAndUserIdForUpdate(snapshot.sourceAccountId(), source.creatorId())
                .orElseThrow();
        settlements.findByIdForUpdate(source.settlementId()).orElseThrow();
        groups.loadActiveForUpdate(e.groupId());
        groups.requireActiveMember(e.groupId(), e.actorId());
        executions.requireLease(e.id(), e.leaseToken(), LocalDateTime.now(ZoneOffset.UTC));
        if (sources.findAwaitingSources(e.groupId()).stream()
                .noneMatch(
                        s ->
                                s.settlementId().equals(source.settlementId())
                                        && s.receivingAccountDigest()
                                                .equals(source.receivingAccountDigest())))
            throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        writer.reconcile(source.settlementId(), source.creatorId(), snapshot, transactions, now);
    }
}
