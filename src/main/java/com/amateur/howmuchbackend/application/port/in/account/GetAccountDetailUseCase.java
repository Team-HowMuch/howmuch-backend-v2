package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;

import java.time.YearMonth;

public interface GetAccountDetailUseCase {
    AccountDetailResult getAccountDetail(Long accountId, YearMonth yearMonth);
}
