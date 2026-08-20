package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.AccountType;

import java.math.BigDecimal;
import java.util.List;

public record LinkedAccountsResult(List<LinkedAccount> accounts) {
    public record LinkedAccount(
            Long id,
            String organization,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency
    ) {
    }
}
