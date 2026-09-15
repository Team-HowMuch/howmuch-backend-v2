package com.amateur.howmuchbackend.infra.treasurer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.treasurer.answer-retention")
public record TreasurerAnswerRetentionProperties(
        boolean enabled,
        Duration retention,
        Duration pollInterval,
        Duration initialDelay,
        int batchSize,
        int maxBatches
) {
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofHours(1);
    private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMinutes(5);
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_BATCHES = 20;

    public TreasurerAnswerRetentionProperties {
        retention = retention == null ? DEFAULT_RETENTION : retention;
        pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        initialDelay = initialDelay == null ? DEFAULT_INITIAL_DELAY : initialDelay;
        batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxBatches = maxBatches == 0 ? DEFAULT_MAX_BATCHES : maxBatches;

        // 보관 기간이 0이거나 음수면 방금 나간 답변까지 지워 신고할 수 없게 된다.
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("AI 비서 답변 보관 기간은 양수여야 합니다.");
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("AI 비서 답변 정리 주기는 양수여야 합니다.");
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("AI 비서 답변 정리 최초 지연은 음수일 수 없습니다.");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("AI 비서 답변 정리 배치 크기는 1..10000이어야 합니다.");
        }
        // 한 주기에 도는 배치 수를 묶어, 밀린 분량이 많아도 정리가 계속 돌며 다른 작업을 굶기지 않게 한다.
        if (maxBatches < 1 || maxBatches > 1_000) {
            throw new IllegalArgumentException("AI 비서 답변 정리 배치 반복 횟수는 1..1000이어야 합니다.");
        }
    }
}
