package com.amateur.howmuchbackend.infra.receipt.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptOcrProperties.class)
public class ReceiptOcrConfig {
    @Bean
    @Qualifier("receiptOcrRestClient")
    RestClient receiptOcrRestClient(RestClient.Builder builder, ReceiptOcrProperties properties) {
        if (!properties.isConfigured()) {
            // 환경 변수를 빠뜨린 배포를 조용히 넘기지 않는다. 이 상태에서 영수증 분석은
            // 전부 503이 되고, 로그가 없으면 원인을 찾는 데 한참이 걸린다.
            log.warn("Receipt OCR base-url is empty. Receipt analysis will answer 503 for every request.");
        }
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
