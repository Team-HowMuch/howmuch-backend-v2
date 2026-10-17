package com.amateur.howmuchbackend.application.external.service;

import com.amateur.howmuchbackend.application.account.service.*;
import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class FinanceAuthenticationCompletionService {
    private final BankConnectionRegistrationService registrations;
    private final BankAccountService accounts;
    private final AccountQueryService transactions;

    public FinanceAuthenticationCompletion complete(long user, FinanceOperationResult result) {
        var operation = result.operation();
        return switch (operation.type()) {
            case "BANK_CONNECT" -> {
                Long connection =
                        registrations.completeAdditionalAuthentication(
                                user, operation, result.registration());
                // The stored completion no longer needs the original ID/PW credentials.
                var safe =
                        new FinanceOperation(
                                operation.type(),
                                operation.organization(),
                                connection,
                                null,
                                null,
                                null,
                                false);
                yield new FinanceAuthenticationCompletion(
                        safe,
                        new BankConnectionResult(
                                connection,
                                operation.organization(),
                                ExternalConnectionStatus.CONNECTED,
                                List.of()),
                        null);
            }
            case "ACCOUNT_LIST" ->
                    new FinanceAuthenticationCompletion(
                            operation,
                            accounts.describeCandidates(
                                    user,
                                    operation.connectionId(),
                                    operation.organization(),
                                    result.accounts()),
                            result.accounts());
            case "TRANSACTION_LIST" -> {
                transactions.completeAdditionalAuthentication(
                        user, operation, result.transactions());
                yield new FinanceAuthenticationCompletion(operation, null, null);
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        };
    }
}
