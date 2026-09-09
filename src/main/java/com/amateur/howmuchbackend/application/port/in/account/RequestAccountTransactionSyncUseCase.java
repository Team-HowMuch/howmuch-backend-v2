package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.RequestAccountTransactionSyncCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncJobResult;

public interface RequestAccountTransactionSyncUseCase {
    AccountTransactionSyncJobResult request(RequestAccountTransactionSyncCommand command);
}
