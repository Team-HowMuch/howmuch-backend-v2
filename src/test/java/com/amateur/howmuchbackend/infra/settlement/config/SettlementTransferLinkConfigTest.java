package com.amateur.howmuchbackend.infra.settlement.config;

import com.amateur.howmuchbackend.adapter.out.transfer.MockSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.adapter.out.transfer.TossSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementTransferLinkConfigTest {
    @ParameterizedTest
    @CsvSource({"none,toss", "local,mock", "dev,mock", "prod,toss"})
    void transferLinkConfig_profileDefaults_exactlyOneAdapter(String profile, String expected) {
        runner(Map.of()).withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SettlementTransferLinkPort.class);
            assertThat(context.getBean(SettlementTransferLinkPort.class))
                    .isInstanceOf(expected.equals("mock")
                            ? MockSettlementTransferLinkAdapter.class : TossSettlementTransferLinkAdapter.class);
        });
    }

    @Test
    void transferLinkConfig_localProfile_mockSelected() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SettlementTransferLinkPort.class);
            assertThat(context.getBean(SettlementTransferLinkPort.class))
                    .isInstanceOf(MockSettlementTransferLinkAdapter.class);
        });
    }

    @ParameterizedTest
    @CsvSource({"local,toss", "dev,toss", "prod,mock", "none,mock", "dev,mock2", "local,mock2"})
    void transferLinkConfig_environmentSelection_overridesProfile(String profile, String selection) {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", selection))
                .withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SettlementTransferLinkPort.class);
                    assertThat(context.getBean(SettlementTransferLinkPort.class))
                            .isInstanceOf(selection.equals("toss")
                                    ? TossSettlementTransferLinkAdapter.class : MockSettlementTransferLinkAdapter.class);
                });
    }

    @Test
    void transferLinkConfig_baseUrl_passedToMockAdapter() {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", "mock",
                "SETTLEMENT_MOCK_TRANSFER_BASE_URL", "http://localhost:3000/send"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    when(context.getBean(BankCatalog.class).findBankName("0020"))
                            .thenReturn(Optional.of("우리은행"));
                    assertThat(context.getBean(SettlementTransferLinkPort.class)
                            .create("0020", "123", 1, "예금주").orElseThrow().url())
                            .startsWith("http://localhost:3000/send?bankCode=0020&bank=");
                });
    }

    @Test
    void transferLinkConfig_devMock_keepsWebDefault() {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", "mock"))
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SettlementTransferLinkPort.class);
                    when(context.getBean(BankCatalog.class).findBankName("0020"))
                            .thenReturn(Optional.of("우리은행"));
                    assertThat(context.getBean(SettlementTransferLinkPort.class)
                            .create("0020", "123", 1, "예금주").orElseThrow().url())
                            .startsWith("https://howmuch-mock-send.vercel.app/send?");
                });
    }

    @Test
    void transferLinkConfig_mock2_usesFixedAppRouteEvenWithWebOverride() {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", "mock2",
                "SETTLEMENT_MOCK_TRANSFER_BASE_URL", "https://web.example.test/send"))
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SettlementTransferLinkPort.class);
                    when(context.getBean(BankCatalog.class).findBankName("0020"))
                            .thenReturn(Optional.of("우리은행"));
                    var link = context.getBean(SettlementTransferLinkPort.class)
                            .create("0020", "1002-000-000002", 12000, "김 하늘&가").orElseThrow();
                    assertThat(link.provider()).isEqualTo("TOSS");
                    assertThat(link.url()).isEqualTo("howmuch-mock://send?bankCode=0020&bank=%EC%9A%B0%EB%A6%AC%EC%9D%80%ED%96%89"
                            + "&accountNo=1002000000002&amount=12000&receiver=%EA%B9%80+%ED%95%98%EB%8A%98%26%EA%B0%80");
                });
    }

    @Test
    void transferLinkConfig_unknownSelection_startupRejected() {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", "typo"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void transferLinkConfig_invalidSelectedMockUrl_startupRejected() {
        runner(Map.of("SETTLEMENT_TRANSFER_LINK_ADAPTER", "mock",
                "SETTLEMENT_MOCK_TRANSFER_BASE_URL", "http://external.example/send"))
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner(Map<String, Object> environment) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("transfer-link-test-environment", environment));
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(SettlementTransferLinkConfig.class, TransferAdapterScan.class)
                .withBean(BankCatalog.class, () -> mock(BankCatalog.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = TossSettlementTransferLinkAdapter.class)
    static class TransferAdapterScan {
    }
}
