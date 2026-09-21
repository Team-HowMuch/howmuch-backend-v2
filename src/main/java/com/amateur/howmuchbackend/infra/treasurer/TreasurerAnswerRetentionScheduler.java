package com.amateur.howmuchbackend.infra.treasurer;

import com.amateur.howmuchbackend.application.treasurer.service.PurgeExpiredTreasurerAnswerService;
import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerAnswerRetentionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.treasurer.answer-retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TreasurerAnswerRetentionScheduler {
    private final PurgeExpiredTreasurerAnswerService purgeExpiredTreasurerAnswerService;
    private final TreasurerAnswerRetentionProperties properties;
    private final MeterRegistry registry;

    @Scheduled(
            fixedDelayString = "${howmuch.treasurer.answer-retention.poll-interval:1h}",
            initialDelayString = "${howmuch.treasurer.answer-retention.initial-delay:5m}"
    )
    public void purge() {
        try {
            int deleted = purgeExpiredTreasurerAnswerService.purgeAll(
                    properties.retention(), properties.batchSize(), properties.maxBatches());
            registry.counter("howmuch.treasurer.answer.retention.deleted").increment(deleted);
            if (deleted > 0) {
                log.info("Purged expired treasurer answers. deleted={}", deleted);
            }
        } catch (RuntimeException exception) {
            // 정리는 실패해도 다음 주기에 다시 시도한다. 답변 제공에는 영향이 없다.
            registry.counter("howmuch.treasurer.answer.retention.failures").increment();
            log.error(
                    "Treasurer answer retention purge failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
