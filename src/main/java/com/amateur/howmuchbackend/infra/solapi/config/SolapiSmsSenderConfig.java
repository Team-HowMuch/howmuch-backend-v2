package com.amateur.howmuchbackend.infra.solapi.config;

import com.solapi.sdk.SolapiClient;
import com.solapi.sdk.message.service.DefaultMessageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SolapiSmsSenderProperties.class)
@ConditionalOnProperty(name = "howmuch.auth.sms.sender", havingValue = "solapi")
public class SolapiSmsSenderConfig {
    @Bean
    DefaultMessageService solapiMessageService(SolapiSmsSenderProperties properties) {
        requireConfigured(properties.apiKey(), "SOLAPI_API_KEY");
        requireConfigured(properties.apiSecret(), "SOLAPI_API_SECRET");
        requireConfigured(properties.sender(), "SOLAPI_SENDER");
        return SolapiClient.INSTANCE.createInstance(properties.apiKey(), properties.apiSecret());
    }

    private void requireConfigured(String value, String environmentVariable) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(environmentVariable + " must be configured.");
        }
    }
}
