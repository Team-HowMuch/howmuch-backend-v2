package com.amateur.howmuchbackend.infra.settlement.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 링크 설정")
class SettlementLinkPropertiesTest {
    @Test
    @DisplayName("외부 정산 링크는 HTTPS 주소만 허용한다")
    void acceptsHttpsExternalBaseUrl() {
        SettlementLinkProperties properties = new SettlementLinkProperties(
                "https://pay.howmuch.example/settlement-links/",
                Duration.ofDays(30)
        );

        assertThat(properties.baseUrl())
                .isEqualTo("https://pay.howmuch.example/settlement-links");
    }

    @Test
    @DisplayName("로컬 개발용 loopback 주소는 HTTP를 허용한다")
    void acceptsHttpLoopbackBaseUrls() {
        assertThat(new SettlementLinkProperties(
                "http://localhost:8080/settlement-links",
                Duration.ofDays(30)
        ).baseUrl()).startsWith("http://localhost:");
        assertThat(new SettlementLinkProperties(
                "http://127.0.0.1:8080/settlement-links",
                Duration.ofDays(30)
        ).baseUrl()).startsWith("http://127.0.0.1:");
        assertThat(new SettlementLinkProperties(
                "http://[::1]:8080/settlement-links",
                Duration.ofDays(30)
        ).baseUrl()).startsWith("http://[::1]:");
    }

    @Test
    @DisplayName("외부 HTTP 정산 링크 주소는 거부한다")
    void rejectsHttpExternalBaseUrl() {
        assertThatThrownBy(() -> new SettlementLinkProperties(
                "http://pay.howmuch.example/settlement-links",
                Duration.ofDays(30)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("localhost와 비슷한 외부 호스트는 HTTP 예외로 인정하지 않는다")
    void rejectsLookalikeLoopbackHost() {
        assertThatThrownBy(() -> new SettlementLinkProperties(
                "http://localhost.example/settlement-links",
                Duration.ofDays(30)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
