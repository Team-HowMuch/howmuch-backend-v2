package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;

public interface GetBankConnectionAccountsUseCase {
    BankConnectionResult getConnectionAccounts(GetBankConnectionAccountsQuery query);
}
