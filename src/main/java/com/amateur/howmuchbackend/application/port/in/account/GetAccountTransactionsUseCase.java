package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;

import java.time.YearMonth;
import java.util.List;

public interface GetAccountTransactionsUseCase {
    List<AccountTransactionResult> getAccountTransactions(Long accountId, YearMonth yearMonth);
}
