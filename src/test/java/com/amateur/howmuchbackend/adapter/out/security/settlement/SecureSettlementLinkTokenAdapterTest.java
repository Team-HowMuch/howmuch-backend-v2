package com.amateur.howmuchbackend.adapter.out.security.settlement;

import com.amateur.howmuchbackend.infra.settlement.config.SettlementLinkProperties;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("보안 정산 링크 토큰 발급")
class SecureSettlementLinkTokenAdapterTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Test
    @DisplayName("URL에는 원본 난수 토큰을 넣고 저장용 SHA-256 hash와 만료 시각을 반환한다")
    void issuesRawUrlAndHashedStorageToken() throws Exception {
        SecureSettlementLinkTokenAdapter adapter = new SecureSettlementLinkTokenAdapter(
                new SettlementLinkProperties("https://howmuch.example/settlements/", Duration.ofDays(7))
        );

        var issued = adapter.issue(NOW);
        String rawToken = issued.url().substring(issued.url().lastIndexOf('/') + 1);
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(rawToken).hasSize(43).matches("^[A-Za-z0-9_-]+$");
        assertThat(issued.tokenHash()).isEqualTo(expectedHash);
        assertThat(issued.url()).startsWith("https://howmuch.example/settlements/");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("43자 URL-safe 형식이 아닌 공개 token은 링크 없음으로 거부한다")
    void rejectsMalformedRawToken() {
        SecureSettlementLinkTokenAdapter adapter = new SecureSettlementLinkTokenAdapter(
                new SettlementLinkProperties("https://howmuch.example/settlements", Duration.ofDays(7))
        );

        assertThatThrownBy(() -> adapter.hash("too-short"))
                .isInstanceOf(ApplicationException.class);
    }
}
