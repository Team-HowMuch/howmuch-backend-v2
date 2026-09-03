package com.amateur.howmuchbackend.infra.settlement.config;

import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SettlementNotificationOutboxProperties.class)
public class SettlementNotificationOutboxConfig {
    @Bean
    SettlementNotificationRetryPolicy settlementNotificationRetryPolicy(
            SettlementNotificationOutboxProperties properties
    ) {
        return new SettlementNotificationRetryPolicy(
                properties.maxAttempts(),
                properties.retryBaseDelay(),
                properties.retryMaxDelay(),
                properties.processingTimeout()
        );
    }
}
