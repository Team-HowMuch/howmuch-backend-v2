package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncResult;

import java.time.YearMonth;

public interface SyncAccountTransactionsUseCase {
    AccountTransactionSyncResult sync(Long accountId, YearMonth yearMonth);
}
