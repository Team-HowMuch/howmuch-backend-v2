package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;

import java.util.List;

public interface GetAccountsUseCase {
    List<AccountSummaryResult> getAccounts(GetAccountsQuery query);
}
