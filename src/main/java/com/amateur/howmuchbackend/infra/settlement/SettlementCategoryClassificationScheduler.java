package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessSettlementCategoryJobsUseCase;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementCategoryClassificationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "howmuch.settlement.category-classification", name = "enabled", havingValue = "true")
public class SettlementCategoryClassificationScheduler {
    private final ProcessSettlementCategoryJobsUseCase useCase;
    private final SettlementCategoryClassificationProperties properties;

    @Scheduled(scheduler = "settlementCategoryClassificationTaskScheduler",
            fixedDelayString = "${howmuch.settlement.category-classification.poll-interval:5s}",
            initialDelayString = "${howmuch.settlement.category-classification.poll-interval:5s}")
    public void processDue() {
        try {
            useCase.processDue(properties.batchSize());
        } catch (RuntimeException failure) {
            log.warn("Settlement category polling failed; durable jobs will be recovered on a later poll");
        }
    }
}
