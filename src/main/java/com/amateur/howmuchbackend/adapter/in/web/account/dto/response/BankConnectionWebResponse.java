package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;

import java.math.BigDecimal;
import java.util.List;

public record BankConnectionWebResponse(
        Long connectionId,
        String organization,
        ExternalConnectionStatus status,
        List<Candidate> candidates
) {
    public static BankConnectionWebResponse from(BankConnectionResult result) {
        return new BankConnectionWebResponse(
                result.connectionId(),
                result.organization(),
                result.status(),
                result.candidates().stream().map(Candidate::from).toList()
        );
    }

    public record Candidate(
            String candidateKey,
            AccountType type,
            String name,
            String maskedNumber,
            BigDecimal balance,
            String currency,
            boolean alreadyLinked
    ) {
        private static Candidate from(BankConnectionResult.Candidate candidate) {
            return new Candidate(
                    candidate.candidateKey(), candidate.type(), candidate.name(), candidate.maskedNumber(),
                    candidate.balance(), candidate.currency(), candidate.alreadyLinked()
            );
        }
    }
}
