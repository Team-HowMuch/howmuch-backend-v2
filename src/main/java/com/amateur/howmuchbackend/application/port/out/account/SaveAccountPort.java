package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.Account;

import java.util.List;

public interface SaveAccountPort {
    List<Account> saveAll(List<Account> accounts);

    int deactivateAccountsForInactiveConnections();
}
