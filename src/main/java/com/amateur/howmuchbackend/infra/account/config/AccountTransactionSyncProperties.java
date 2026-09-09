package com.amateur.howmuchbackend.infra.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "howmuch.account.transaction-sync")
public record AccountTransactionSyncProperties(
        Set<Long> canaryAccountIds,
        Connector connector,
        Outbox outbox
) {
    public AccountTransactionSyncProperties {
        canaryAccountIds = canaryAccountIds == null ? Set.of() : Set.copyOf(canaryAccountIds);
        if (canaryAccountIds.stream().anyMatch(accountId -> accountId == null || accountId < 1)) {
            throw new IllegalArgumentException("거래 동기화 canary 계좌 ID는 양수여야 합니다.");
        }
        connector = connector == null ? new Connector(null, null, null, null) : connector;
        outbox = outbox == null ? new Outbox(false, null, null, null, 0) : outbox;
        if (!canaryAccountIds.isEmpty() && connector.serviceToken().isBlank()) {
            throw new IllegalArgumentException("비동기 거래 동기화 활성화 시 Connector service token이 필요합니다.");
        }
    }

    public record Connector(
            String baseUrl,
            String serviceToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8081";

        public Connector {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
            serviceToken = serviceToken == null ? "" : serviceToken;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
            if (connectTimeout.isZero() || connectTimeout.isNegative()
                    || readTimeout.isZero() || readTimeout.isNegative()) {
                throw new IllegalArgumentException("Connector timeout은 양수여야 합니다.");
            }
            requireLoopback(baseUrl);
        }

        private static void requireLoopback(String baseUrl) {
            URI uri = URI.create(baseUrl);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("Connector base URL이 올바르지 않습니다.");
            }
            try {
                if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                    throw new IllegalArgumentException("Connector base URL은 loopback 주소여야 합니다.");
                }
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Connector base URL host를 확인할 수 없습니다.", exception);
            }
        }
    }

    public record Outbox(
            boolean enabled,
            String commandTopic,
            Duration pollInterval,
            Duration initialDelay,
            int batchSize
    ) {
        public Outbox {
            commandTopic = commandTopic == null || commandTopic.isBlank()
                    ? "finance.sync.command.v1" : commandTopic;
            pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
            initialDelay = initialDelay == null ? Duration.ofSeconds(1) : initialDelay;
            batchSize = batchSize == 0 ? 50 : batchSize;
            if (pollInterval.isZero() || pollInterval.isNegative()
                    || initialDelay.isZero() || initialDelay.isNegative()
                    || batchSize < 1) {
                throw new IllegalArgumentException("동기화 Outbox 설정은 양수여야 합니다.");
            }
        }
    }
}
