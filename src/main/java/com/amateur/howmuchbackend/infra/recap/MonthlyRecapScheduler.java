package com.amateur.howmuchbackend.infra.recap;

import com.amateur.howmuchbackend.application.port.in.recap.CleanRecapImagesUseCase;
import com.amateur.howmuchbackend.application.port.in.recap.ProcessMonthlyRecapsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "howmuch.recap.generation", name = "enabled", havingValue = "true")
public class MonthlyRecapScheduler {
    private final ProcessMonthlyRecapsUseCase generation;
    private final CleanRecapImagesUseCase cleanup;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        generation.recoverAndRegister();
        ready = true;
    }

    // Includes the first day at 00:10 KST and catches months missed during downtime.
    @Scheduled(cron = "0 10 * * * *", zone = "Asia/Seoul")
    public void registerAndRecover() {
        if (!ready) return;
        try { generation.recoverAndRegister(); }
        catch (RuntimeException exception) {
            log.error("Recap registration failed. failureType={}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${howmuch.recap.generation.poll-interval:30s}", initialDelayString = "30s")
    public void poll() {
        if (!ready) return;
        try { generation.processBatch(100); }
        catch (RuntimeException exception) {
            log.error("Recap processing failed. failureType={}", exception.getClass().getSimpleName());
        }
        try { cleanup.cleanBatch(100); }
        catch (RuntimeException exception) {
            log.error("Recap cleanup failed. failureType={}", exception.getClass().getSimpleName());
        }
    }
}
