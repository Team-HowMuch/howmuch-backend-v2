package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({SettlementNotificationOutboxProperties.class, SettlementAutomaticReminderProperties.class})
public class SettlementNotificationOutboxConfig {
}
