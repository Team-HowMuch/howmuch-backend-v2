package com.amateur.howmuchbackend.infra.account.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.port.in.account.RunCertificateReferenceWorkUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class CertificateReferenceWorkerSchedulerTest {
    @Test
    void independentLoopsEnterBlockedProviderWorkConcurrentlyAndStayBounded() throws Exception {
        var executor = new ThreadPoolTaskScheduler();
        executor.setPoolSize(2);
        executor.initialize();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var worker = mock(RunCertificateReferenceWorkUseCase.class);
        doAnswer(
                        call -> {
                            int n = active.incrementAndGet();
                            maximum.accumulateAndGet(n, Math::max);
                            entered.countDown();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } finally {
                                active.decrementAndGet();
                            }
                            return null;
                        })
                .when(worker)
                .executeOne();
        var scheduler =
                new CertificateReferenceWorkerScheduler(
                        worker,
                        executor,
                        new CertificateReferenceProperties(true, null, null, null, 2),
                        10);
        try {
            scheduler.start();
            scheduler.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(2);
            assertThat(scheduler.isRunning()).isTrue();
            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();
        } finally {
            scheduler.stop();
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void invalidConcurrencyCannotCreateAnUnboundedExecutor() {
        for (int n : new int[] {-1, 17})
            assertThatThrownBy(() -> new CertificateReferenceProperties(true, null, null, null, n))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new CertificateReferenceProperties(true, null, null, null, 0)
                                .workerConcurrency())
                .isEqualTo(4);
    }
}
