package com.amateur.howmuchbackend.adapter.out.configuration;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncEligibilityPort;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountTransactionSyncEligibilityAdapter implements AccountTransactionSyncEligibilityPort {
    private final AccountTransactionSyncProperties properties;

    @Override
    public boolean isEnabled(Long accountId) {
        return properties.canaryAccountIds().contains(accountId);
    }
}
