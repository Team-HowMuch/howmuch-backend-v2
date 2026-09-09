package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncJobResult;

import java.util.UUID;

public interface GetAccountTransactionSyncJobUseCase {
    AccountTransactionSyncJobResult get(UUID syncJobId);
}
