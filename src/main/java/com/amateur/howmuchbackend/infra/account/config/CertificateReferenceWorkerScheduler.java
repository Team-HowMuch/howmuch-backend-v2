package com.amateur.howmuchbackend.infra.account.config;

import com.amateur.howmuchbackend.application.port.in.account.RunCertificateReferenceWorkUseCase;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Component
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceWorkerScheduler implements SmartLifecycle {
    private final RunCertificateReferenceWorkUseCase worker;
    private final ThreadPoolTaskScheduler executor;
    private final int concurrency;
    private final Duration delay;
    private final List<ScheduledFuture<?>> loops = new ArrayList<>();
    private boolean running;

    public CertificateReferenceWorkerScheduler(
            RunCertificateReferenceWorkUseCase worker,
            @Qualifier("certificateReferenceExecutionScheduler") ThreadPoolTaskScheduler executor,
            CertificateReferenceProperties properties,
            @Value("${howmuch.certificate-reference.worker-delay-ms:1000}") long delayMillis) {
        if (delayMillis < 1)
            throw new IllegalArgumentException("Reference worker delay must be positive");
        this.worker = worker;
        this.executor = executor;
        this.concurrency = properties.workerConcurrency();
        this.delay = Duration.ofMillis(delayMillis);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        // Each scheduled future is an independent loop; increasing one @Scheduled task's pool does
        // not parallelize it.
        for (int i = 0; i < concurrency; i++)
            loops.add(executor.scheduleWithFixedDelay(worker::executeOne, delay));
        running = true;
    }

    @Override
    public synchronized void stop() {
        loops.forEach(loop -> loop.cancel(false));
        loops.clear();
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate-reference.relay-delay-ms:500}",
            scheduler = "certificateReferenceRelayScheduler")
    public void publish() {
        worker.publishOne();
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate-reference.recovery-delay-ms:10000}",
            scheduler = "certificateReferenceRelayScheduler")
    public void recover() {
        worker.recover();
    }
}
