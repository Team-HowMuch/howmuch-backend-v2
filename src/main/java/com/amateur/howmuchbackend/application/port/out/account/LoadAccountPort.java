package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.Account;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LoadAccountPort {
    Set<String> findCandidateKeys(Long connectionId);

    List<Account> findActiveByUserId(Long userId);

    Optional<Account> findActiveByIdAndUserId(Long accountId, Long userId);
}
