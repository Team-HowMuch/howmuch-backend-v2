package com.amateur.howmuchbackend.infra.settlement.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.amateur.howmuchbackend.infra.settlement.config.SettlementTransferLinkProperties.Adapter.MOCK;
import static com.amateur.howmuchbackend.infra.settlement.config.SettlementTransferLinkProperties.Adapter.TOSS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTransferLinkPropertiesTest {
    @Test
    void defaultsToToss() {
        assertThat(new SettlementTransferLinkProperties(null, null).adapter()).isEqualTo(TOSS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://howmuch-mock-send.vercel.app/send", "http://localhost:3000/send",
            "http://127.0.0.1:3000/send", "http://[::1]:3000/send"})
    void acceptsHttpsAndLoopback(String url) {
        var properties = new SettlementTransferLinkProperties(MOCK,
                new SettlementTransferLinkProperties.Mock(url));
        assertThat(properties.mock().baseUrl()).isEqualTo(url);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/send", "http://example.com/send", "http://localhost.example.com/send",
            "https://example.com/send?sender=x", "https://example.com/send#fragment",
            "https://user@example.com/send", "javascript:alert(1)", "https://invalid host/send"})
    void rejectsInvalidMockBaseUrl(String url) {
        assertThatThrownBy(() -> new SettlementTransferLinkProperties(MOCK,
                new SettlementTransferLinkProperties.Mock(url)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mock transfer base URL must be HTTPS (or HTTP loopback), without user info, query or fragment.")
                .hasNoCause();
    }

    @Test
    void unusedMockSettingsDoNotPreventTossStartup() {
        assertThat(new SettlementTransferLinkProperties(TOSS,
                new SettlementTransferLinkProperties.Mock("not a URL")).adapter()).isEqualTo(TOSS);
    }
}
