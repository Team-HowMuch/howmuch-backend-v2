package com.amateur.howmuchbackend.adapter.out.connector;

import com.amateur.howmuchbackend.application.port.out.external.FinanceConnectorRegistrationPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class FinanceConnectorRegistrationAdapterTest {
    @Test
    void sendsServiceTokenAndCompleteAccountContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:8081");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID connectionRef = UUID.randomUUID();
        UUID accountRef = UUID.randomUUID();
        server.expect(once(), requestTo("http://127.0.0.1:8081/internal/v1/connections/" + connectionRef))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().json("""
                        {
                          "connectedId":"connected-id",
                          "organizationCode":"0020",
                          "accounts":[{
                            "accountRef":"%s",
                            "providerAccountRef":"10020001",
                            "accountType":"DEPOSIT_TRUST",
                            "productCode":"11",
                            "currency":"KRW"
                          }]
                        }
                        """.formatted(accountRef)))
                .andRespond(withSuccess("""
                        {"connectionRef":"%s","connectionVersion":2,"changed":true}
                        """.formatted(connectionRef), MediaType.APPLICATION_JSON));
        var adapter = new FinanceConnectorRegistrationAdapter(builder.build(), properties());

        var result = adapter.register(new FinanceConnectorRegistrationPort.Registration(
                connectionRef,
                "connected-id",
                "0020",
                List.of(new FinanceConnectorRegistrationPort.Account(
                        accountRef,
                        "10020001",
                        AccountType.DEPOSIT_TRUST,
                        "11",
                        "KRW"
                ))
        ));

        assertThat(result.connectionVersion()).isEqualTo(2);
        server.verify();
    }

    @Test
    void mapsConnectorFailureAndInvalidVersionToSafeUnavailableError() {
        UUID connectionRef = UUID.randomUUID();
        RestClient.Builder failingBuilder = RestClient.builder().baseUrl("http://127.0.0.1:8081");
        MockRestServiceServer failingServer = MockRestServiceServer.bindTo(failingBuilder).build();
        failingServer.expect(once(), requestTo(
                        "http://127.0.0.1:8081/internal/v1/connections/" + connectionRef
                ))
                .andRespond(withServerError());

        assertUnavailable(new FinanceConnectorRegistrationAdapter(
                failingBuilder.build(), properties()
        ), connectionRef);
        failingServer.verify();

        RestClient.Builder invalidBuilder = RestClient.builder().baseUrl("http://127.0.0.1:8081");
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        invalidServer.expect(once(), requestTo(
                        "http://127.0.0.1:8081/internal/v1/connections/" + connectionRef
                ))
                .andRespond(withSuccess("""
                        {"connectionRef":"%s","connectionVersion":0,"changed":false}
                        """.formatted(connectionRef), MediaType.APPLICATION_JSON));

        assertUnavailable(new FinanceConnectorRegistrationAdapter(
                invalidBuilder.build(), properties()
        ), connectionRef);
        invalidServer.verify();
    }

    private void assertUnavailable(FinanceConnectorRegistrationAdapter adapter, UUID connectionRef) {
        assertThatThrownBy(() -> adapter.register(new FinanceConnectorRegistrationPort.Registration(
                connectionRef,
                "connected-id",
                "0020",
                List.of()
        )))
                .isInstanceOf(ApplicationException.class)
                .satisfies(exception -> assertThat(((ApplicationException) exception).getStatusCode())
                        .isEqualTo(AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE));
    }

    private AccountTransactionSyncProperties properties() {
        return new AccountTransactionSyncProperties(
                Set.of(1L),
                new AccountTransactionSyncProperties.Connector(
                        "http://127.0.0.1:8081",
                        "test-token",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                ),
                null
        );
    }
}
