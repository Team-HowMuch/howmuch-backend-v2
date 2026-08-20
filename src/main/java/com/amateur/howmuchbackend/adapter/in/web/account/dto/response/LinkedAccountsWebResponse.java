package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.LinkedAccountsResult;
import com.amateur.howmuchbackend.domain.account.AccountType;

import java.math.BigDecimal;
import java.util.List;

public record LinkedAccountsWebResponse(List<LinkedAccount> accounts) {
    public static LinkedAccountsWebResponse from(LinkedAccountsResult result) {
        return new LinkedAccountsWebResponse(result.accounts().stream().map(LinkedAccount::from).toList());
    }

    public record LinkedAccount(
            Long id,
            String organization,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency
    ) {
        private static LinkedAccount from(LinkedAccountsResult.LinkedAccount account) {
            return new LinkedAccount(
                    account.id(), account.organization(), account.type(), account.name(), account.maskedNumber(),
                    account.balance(), account.currency()
            );
        }
    }
}
