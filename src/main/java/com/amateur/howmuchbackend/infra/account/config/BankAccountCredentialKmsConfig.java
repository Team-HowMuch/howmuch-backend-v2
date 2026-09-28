package com.amateur.howmuchbackend.infra.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Duration;

@Configuration
public class BankAccountCredentialKmsConfig {
    @Bean
    @Lazy
    KmsClient bankAccountCredentialKmsClient() {
        return KmsClient.builder().overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofSeconds(2))
                .apiCallTimeout(Duration.ofSeconds(5))
                .build()).build();
    }
}
