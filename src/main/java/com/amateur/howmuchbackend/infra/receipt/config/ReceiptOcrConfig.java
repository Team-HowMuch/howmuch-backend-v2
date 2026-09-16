package com.amateur.howmuchbackend.infra.receipt.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptOcrProperties.class)
public class ReceiptOcrConfig {
    @Bean
    @Qualifier("receiptOcrRestClient")
    RestClient receiptOcrRestClient(RestClient.Builder builder, ReceiptOcrProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .requestFactory(factory)
                .build();
    }
}
