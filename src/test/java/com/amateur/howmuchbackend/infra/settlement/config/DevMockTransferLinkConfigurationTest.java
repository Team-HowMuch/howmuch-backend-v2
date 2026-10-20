package com.amateur.howmuchbackend.infra.settlement.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DevMockTransferLinkConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("spring.profiles.active=dev");

    @Test
    void devProfile_usesNativeMockAppWithoutStartingInfrastructure() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(SettlementTransferLinkProperties.class);
            assertThat(properties.adapter()).isEqualTo(SettlementTransferLinkProperties.Adapter.MOCK);
            assertThat(properties.mock().baseUrl()).isEqualTo("howmuch-mock://send");
        });
    }

    @Test
    void devProfile_explicitWebMockOverrideIsPreserved() {
        context.withPropertyValues("SETTLEMENT_MOCK_TRANSFER_BASE_URL=https://howmuch-mock-send.vercel.app/send")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(SettlementTransferLinkProperties.class).mock().baseUrl())
                            .isEqualTo("https://howmuch-mock-send.vercel.app/send");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettlementTransferLinkProperties.class)
    static class PropertiesConfiguration {
    }
}
