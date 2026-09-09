package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.domain.account.AccountType;

import java.util.List;
import java.util.UUID;

public interface FinanceConnectorRegistrationPort {
    RegistrationResult register(Registration registration);

    record Registration(
            UUID connectionRef,
            String connectedId,
            String organizationCode,
            List<Account> accounts
    ) {
    }

    record Account(
            UUID accountRef,
            String providerAccountRef,
            AccountType accountType,
            String productCode,
            String currency
    ) {
    }

    record RegistrationResult(long connectionVersion) {
    }
}
