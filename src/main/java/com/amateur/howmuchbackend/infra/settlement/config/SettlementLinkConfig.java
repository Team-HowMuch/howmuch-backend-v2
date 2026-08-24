package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        SettlementLinkProperties.class,
        SettlementParticipantContextProperties.class
})
public class SettlementLinkConfig {
}
