package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.out.account.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.domain.account.Transaction;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NettingObservedTransactionWriter {
    private final LoadAccountPort accounts;
    private final SaveAccountTransactionPort transactions;
    private final NettingExecutionStorePort executions;

    @Transactional
    public void save(
            NettingExecutionStorePort.Execution execution,
            LoadGroupNettingPort.ReceivingAccount account,
            List<Transaction> fetched,
            LocalDateTime now) {
        accounts.findActiveByIdAndUserIdForUpdate(account.accountId(), account.userId())
                .orElseThrow();
        now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        executions.requireLease(execution.id(), execution.leaseToken(), now);
        transactions.saveAllIgnoringDuplicates(fetched);
    }
}
