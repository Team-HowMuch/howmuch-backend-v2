package com.amateur.howmuchbackend.adapter.out.connector;

import com.amateur.howmuchbackend.application.port.out.external.FinanceConnectorRegistrationPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Component
public class FinanceConnectorRegistrationAdapter implements FinanceConnectorRegistrationPort {
    private final RestClient restClient;
    private final AccountTransactionSyncProperties properties;

    public FinanceConnectorRegistrationAdapter(
            @Qualifier("financeConnectorRestClient") RestClient restClient,
            AccountTransactionSyncProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public RegistrationResult register(Registration registration) {
        try {
            RegistrationResponse response = restClient.put()
                    .uri("/internal/v1/connections/{connectionRef}", registration.connectionRef())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.connector().serviceToken())
                    .body(new RegistrationRequest(
                            registration.connectedId(),
                            registration.organizationCode(),
                            registration.accounts().stream().map(AccountRequest::from).toList()
                    ))
                    .retrieve()
                    .body(RegistrationResponse.class);
            if (response == null
                    || !registration.connectionRef().equals(response.connectionRef())
                    || response.connectionVersion() < 1) {
                throw ApplicationException.of(
                        AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE
                );
            }
            return new RegistrationResult(response.connectionVersion());
        } catch (RestClientException exception) {
            throw ApplicationException.of(
                    AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE,
                    exception
            );
        }
    }

    private record RegistrationRequest(
            String connectedId,
            String organizationCode,
            List<AccountRequest> accounts
    ) {
    }

    private record AccountRequest(
            UUID accountRef,
            String providerAccountRef,
            String accountType,
            String productCode,
            String currency
    ) {
        private static AccountRequest from(Account account) {
            return new AccountRequest(
                    account.accountRef(),
                    account.providerAccountRef(),
                    account.accountType().name(),
                    account.productCode(),
                    account.currency()
            );
        }
    }

    private record RegistrationResponse(UUID connectionRef, long connectionVersion, boolean changed) {
    }
}
