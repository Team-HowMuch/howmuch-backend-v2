package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.in.recap.PublishRecapEventsUseCase;
import com.amateur.howmuchbackend.application.port.out.recap.RecapEventPublisherPort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapPublicationStorePort;
import com.amateur.howmuchbackend.application.recap.policy.RecapPublicationRetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.recap.publication", name = "enabled", havingValue = "true")
public class RecapPublicationRelay implements PublishRecapEventsUseCase {
    private final RecapPublicationStorePort store;
    private final RecapEventPublisherPort publisher;
    private final RecapPublicationRetryPolicy retryPolicy;
    private final Clock clock;

    @Override
    public int publishBatch(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recap publication batch must be 1..100.");
        store.recoverExpired(now(), limit);
        int published = 0;
        for (int claimed = 0; claimed < limit; claimed++) {
            var now = now();
            var candidate = store.claimNext(now, now.plusMinutes(2));
            if (candidate.isEmpty()) break;
            var claim = candidate.get();
            try {
                publisher.publish(claim.message());
            } catch (RuntimeException exception) {
                now = now();
                store.retry(claim, now, retryPolicy.nextAttemptAt(claim.attemptCount(), now));
                continue;
            }
            // An ACK followed by a failed/expired DB claim may redeliver the same stable event.
            if (store.published(claim, now())) published++;
        }
        return published;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
