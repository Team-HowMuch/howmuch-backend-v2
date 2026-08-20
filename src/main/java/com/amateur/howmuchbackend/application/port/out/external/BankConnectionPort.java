package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.domain.account.AccountType;

import java.math.BigDecimal;
import java.util.List;

public interface BankConnectionPort {
    ConnectionResult create(BankRegistration registration);

    ConnectionResult add(String connectedId, BankRegistration registration);

    List<ExternalAccountCandidate> findAccounts(String connectedId, String organization, String birthDate);

    record BankRegistration(String organization, String loginId, String password, String birthDate) {
    }

    record ConnectionResult(String connectedId, String resultCode) {
    }

    record ExternalAccountCandidate(
            String externalAccountKey,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency
    ) {
    }
}
