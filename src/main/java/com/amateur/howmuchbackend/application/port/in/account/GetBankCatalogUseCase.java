package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;

import java.util.List;

public interface GetBankCatalogUseCase {
    List<BankCatalogResult> getBanks();
}
