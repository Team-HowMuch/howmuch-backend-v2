package com.amateur.howmuchbackend.infra.observability;

import com.amateur.howmuchbackend.application.settlement.service.SettlementNotificationOutboxWorker;
import com.amateur.howmuchbackend.infra.settlement.SettlementNotificationOutboxScheduler;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementNotificationOutboxProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettlementOutboxMetricsTest {
    @Test
    void reusesBatchOutcomesAndCountsPollingFailure() {
        var worker = mock(SettlementNotificationOutboxWorker.class);
        var registry = new SimpleMeterRegistry();
        try {
            var scheduler = new SettlementNotificationOutboxScheduler(worker,
                    new SettlementNotificationOutboxProperties(null, null, 50), registry);
            when(worker.processBatch(50)).thenReturn(new SettlementNotificationOutboxWorker.BatchResult(2, 10, 4, 3, 2, 1));
            scheduler.poll();
            assertThat(registry.get("howmuch.settlement.outbox.events").tag("outcome", "retried").counter().count()).isEqualTo(3);
            assertThat(registry.get("howmuch.settlement.outbox.events").tag("outcome", "failed").counter().count()).isEqualTo(2);
            assertThat(registry.get("howmuch.settlement.outbox.events").tag("outcome", "succeeded").counter().count()).isEqualTo(4);
            assertThat(registry.get("howmuch.settlement.outbox.events").tag("outcome", "ownership_lost").counter().count()).isEqualTo(1);
            when(worker.processBatch(50)).thenThrow(new IllegalStateException("synthetic-sensitive-failure"));
            assertThatCode(scheduler::poll).doesNotThrowAnyException();
            assertThat(registry.get("howmuch.settlement.outbox.poll.failures").counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("outcome")));
        } finally {
            registry.close();
        }
    }
}
