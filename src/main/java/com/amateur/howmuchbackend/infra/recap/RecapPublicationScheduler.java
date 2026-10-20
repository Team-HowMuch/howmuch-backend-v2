package com.amateur.howmuchbackend.infra.recap;

import com.amateur.howmuchbackend.application.port.in.recap.PublishRecapEventsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "howmuch.recap.publication", name = "enabled", havingValue = "true")
public class RecapPublicationScheduler {
    private final PublishRecapEventsUseCase publication;

    @Scheduled(fixedDelayString = "${howmuch.recap.publication.poll-interval:10s}",
            initialDelayString = "${howmuch.recap.publication.initial-delay:30s}",
            scheduler = "recapPublicationTaskScheduler")
    public void publish() {
        try { publication.publishBatch(100); }
        catch (RuntimeException exception) {
            log.warn("Recap publication deferred. failureType={}", exception.getClass().getSimpleName());
        }
    }
}
