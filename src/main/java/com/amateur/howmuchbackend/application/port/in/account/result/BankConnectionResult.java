package com.amateur.howmuchbackend.application.port.in.account.result;

import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;

import java.math.BigDecimal;
import java.util.List;

public record BankConnectionResult(
        Long connectionId,
        String organization,
        ExternalConnectionStatus status,
        List<Candidate> candidates
) {
    public record Candidate(
            String candidateKey,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency,
            boolean alreadyLinked
    ) {
    }
}
