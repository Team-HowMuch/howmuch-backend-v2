package com.amateur.howmuchbackend.infra.recap;

import com.amateur.howmuchbackend.application.port.in.recap.PublishRecapEventsUseCase;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementCategoryClassificationConfig;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementTransferReconciliationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecapPublicationSchedulingConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RecapPublicationSchedulingConfig.class, RecapPublicationScheduler.class,
                    SchedulingEnabled.class);

    @Test
    void disabledPublicationCreatesNoSchedulerOrPoller() {
        context.run(app -> assertThat(app).hasNotFailed()
                .doesNotHaveBean(RecapPublicationScheduler.class)
                .doesNotHaveBean("recapPublicationTaskScheduler")
                .doesNotHaveBean("taskScheduler"));
    }

    @Test
    void publicationAloneRetainsASeparateSharedScheduler() {
        context.withPropertyValues("howmuch.recap.publication.enabled=true",
                        "howmuch.recap.publication.initial-delay=1h")
                .withBean(PublishRecapEventsUseCase.class, () -> mock(PublishRecapEventsUseCase.class))
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var publication = app.getBean("recapPublicationTaskScheduler", ThreadPoolTaskScheduler.class);
                    var shared = app.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                    assertThat(publication).isNotSameAs(shared);
                    assertThat(publication.getThreadNamePrefix()).isEqualTo("recap-publication-");
                    assertThat(publication.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
                    assertThat(shared.getThreadNamePrefix()).isEqualTo("scheduling-");
                    assertThat(shared.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
                });
    }

    @Test
    void blockingPublicationDoesNotBlockSharedOrReconciliationScheduledWork() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publicationThread = new AtomicReference<String>();
        var probe = new OtherScheduledWork();
        PublishRecapEventsUseCase blockingRelay = limit -> {
            publicationThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return 0;
        };

        context.withUserConfiguration(SettlementTransferReconciliationConfig.class,
                        SettlementCategoryClassificationConfig.class)
                .withPropertyValues("howmuch.recap.publication.enabled=true",
                        "howmuch.recap.publication.initial-delay=0s", "howmuch.recap.publication.poll-interval=1h",
                        "howmuch.settlement.transfer-reconciliation.enabled=true",
                        "howmuch.settlement.category-classification.enabled=true")
                .withBean(PublishRecapEventsUseCase.class, () -> blockingRelay)
                .withBean(OtherScheduledWork.class, () -> probe)
                .run(app -> {
                    try {
                        assertThat(app).hasNotFailed();
                        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                        probe.observe.set(true);
                        assertThat(probe.sharedTick.await(3, TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.reconciliationTick.await(3, TimeUnit.SECONDS)).isTrue();
                        assertThat(release.getCount()).isOne();
                        assertThat(publicationThread.get()).startsWith("recap-publication-");
                        assertThat(probe.sharedThread.get()).startsWith("scheduling-");
                        assertThat(probe.reconciliationThread.get()).startsWith("settlement-transfer-reconciliation-");
                        assertThat(app.getBeansOfType(ThreadPoolTaskScheduler.class)).hasSize(4);
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {}

    static class OtherScheduledWork {
        final AtomicBoolean observe = new AtomicBoolean();
        final CountDownLatch sharedTick = new CountDownLatch(1);
        final CountDownLatch reconciliationTick = new CountDownLatch(1);
        final AtomicReference<String> sharedThread = new AtomicReference<>();
        final AtomicReference<String> reconciliationThread = new AtomicReference<>();

        @Scheduled(fixedDelay = 10)
        public void shared() {
            if (observe.get()) {
                sharedThread.set(Thread.currentThread().getName());
                sharedTick.countDown();
            }
        }

        @Scheduled(fixedDelay = 10, scheduler = "settlementTransferReconciliationTaskScheduler")
        public void reconciliation() {
            if (observe.get()) {
                reconciliationThread.set(Thread.currentThread().getName());
                reconciliationTick.countDown();
            }
        }
    }
}
