package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.Account;

import java.util.List;
import java.util.Set;

public interface AccountStorePort {
    Set<String> findCandidateKeys(Long connectionId);

    List<Account> saveAll(List<Account> accounts);

    int deactivateAccountsForInactiveConnections();
}
