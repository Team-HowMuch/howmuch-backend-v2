package com.amateur.howmuchbackend.infra.treasurer.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TreasurerLlmProperties.class)
public class TreasurerConfig {
    @Bean
    Clock treasurerClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Qualifier("treasurerLlmRestClient")
    RestClient treasurerLlmRestClient(RestClient.Builder builder, TreasurerLlmProperties properties) {
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
