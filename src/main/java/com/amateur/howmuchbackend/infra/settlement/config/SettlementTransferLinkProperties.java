package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "howmuch.settlement.transfer-link")
public record SettlementTransferLinkProperties(Adapter adapter, Mock mock) {
    private static final String DEFAULT_MOCK_BASE_URL = "https://howmuch-mock-send.vercel.app/send";

    public SettlementTransferLinkProperties {
        adapter = adapter == null ? Adapter.TOSS : adapter;
        mock = mock == null ? new Mock(DEFAULT_MOCK_BASE_URL) : mock;
        if (adapter == Adapter.MOCK) {
            mock = new Mock(validateMockBaseUrl(mock.baseUrl()));
        }
    }

    public enum Adapter {
        TOSS, MOCK
    }

    public record Mock(String baseUrl) {
    }

    private static String validateMockBaseUrl(String value) {
        String message = "Mock transfer base URL must be HTTPS (or HTTP loopback), without user info, query or fragment.";
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String normalized = value.strip();
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            // 원본 URL이 포함된 URI 예외를 설정 오류에 연결하지 않는다.
            throw new IllegalArgumentException(message);
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost())))) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        return switch (host.toLowerCase(Locale.ROOT)) {
            case "localhost", "127.0.0.1", "[::1]", "::1" -> true;
            default -> false;
        };
    }
}
