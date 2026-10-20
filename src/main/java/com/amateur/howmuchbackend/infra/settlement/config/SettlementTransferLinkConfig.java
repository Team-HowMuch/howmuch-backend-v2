package com.amateur.howmuchbackend.infra.settlement.config;

import com.amateur.howmuchbackend.adapter.out.transfer.MockSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.adapter.out.transfer.TossSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettlementTransferLinkProperties.class)
public class SettlementTransferLinkConfig {
    @Bean
    SettlementTransferLinkPort settlementTransferLinkPort(
            BankCatalog bankCatalog,
            SettlementTransferLinkProperties properties
    ) {
        return switch (properties.adapter()) {
            case TOSS -> new TossSettlementTransferLinkAdapter(bankCatalog);
            case MOCK -> new MockSettlementTransferLinkAdapter(bankCatalog, properties.mock().baseUrl());
            case MOCK2 -> new MockSettlementTransferLinkAdapter(bankCatalog, "howmuch-mock://send");
        };
    }
}
