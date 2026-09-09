package com.amateur.howmuchbackend.infra.account.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AccountTransactionSyncProperties.class)
public class AccountTransactionSyncConfig {
    @Bean
    @Qualifier("financeConnectorRestClient")
    RestClient financeConnectorRestClient(
            RestClient.Builder builder,
            AccountTransactionSyncProperties properties
    ) {
        var connector = properties.connector();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connector.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(connector.readTimeout());
        return builder.clone()
                .baseUrl(connector.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
