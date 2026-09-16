package com.amateur.howmuchbackend.infra.receipt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.receipt.ocr")
public record ReceiptOcrProperties(
        String baseUrl,
        String path,
        Duration connectTimeout,
        Duration readTimeout
) {
    private static final String DEFAULT_PATH = "/ocr/receipt";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // OCR 한 건이 약 3초다. 넉넉히 두되 무한정 기다리지는 않는다.
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);

    public ReceiptOcrProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        path = StringUtils.hasText(path) ? path.trim() : DEFAULT_PATH;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;

        if (StringUtils.hasText(baseUrl)) {
            baseUrl = validateBaseUrl(baseUrl);
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("영수증 OCR 경로는 /로 시작해야 합니다.");
        }
        requirePositive(connectTimeout, "연결 제한 시간");
        requirePositive(readTimeout, "응답 제한 시간");
    }

    /**
     * 주소가 비어 있으면 연동이 꺼진 것으로 본다.
     *
     * <p>테스트와 로컬에서 내부 장비를 부르지 않게 하려면 값을 비우면 된다.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    public String endpoint() {
        return baseUrl + path;
    }

    /**
     * 자격 증명이나 질의가 섞인 주소를 막는다. 설정 실수로 이미지를 엉뚱한 곳에 보내지 않는다.
     */
    private static String validateBaseUrl(String value) {
        String message = "영수증 OCR 주소는 사용자 정보·질의·프래그먼트 없는 HTTP(S) 주소여야 합니다.";
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(message, exception);
        }
        boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!http || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(message);
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void requirePositive(Duration value, String label) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("영수증 OCR %s은(는) 양수여야 합니다.".formatted(label));
        }
    }
}
