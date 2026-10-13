package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.in.recap.BuildMonthlyRecapUseCase;
import com.amateur.howmuchbackend.application.port.out.recap.LoadMonthlyRecapPort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapGenerationJobStorePort;
import com.amateur.howmuchbackend.application.recap.model.RecapGenerationClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecapGenerationWorker implements com.amateur.howmuchbackend.application.port.in.recap.ProcessMonthlyRecapsUseCase {
    private final RecapGenerationJobStorePort jobs;
    private final LoadMonthlyRecapPort snapshots;
    private final BuildMonthlyRecapUseCase builder;
    private final RecapGenerationCompletionService completion;
    private final com.amateur.howmuchbackend.application.recap.policy.RecapGenerationSchedulePolicy schedule;
    private final com.amateur.howmuchbackend.application.port.out.recap.RecapImageStoragePort images;

    public void recoverAndRegister() {
        images.verifyReady();
        jobs.recoverExpired(now());
        var now = now();
        var window = schedule.window(now);
        jobs.registerMissingMonths(window.month(), now, window.availableAt());
    }

    public int processBatch(int limit) {
        jobs.recoverExpired(now());
        int count = 0;
        while (count < limit) {
            var claim = jobs.claimNext(now());
            if (claim.isEmpty()) break;
            process(claim.get());
            count++;
        }
        return count;
    }

    private void process(RecapGenerationClaim claim) {
        try (var heartbeat = Executors.newSingleThreadScheduledExecutor()) {
            var renewal = heartbeat.scheduleAtFixedRate(() -> {
                try { jobs.renew(claim, now()); }
                catch (RuntimeException exception) {
                    log.warn("Recap lease renewal failed. failureType={}", exception.getClass().getSimpleName());
                }
            }, 1, 1, TimeUnit.MINUTES);
            try {
                var existing = snapshots.find(claim.ownerId(), claim.month());
                var recap = existing.isPresent() ? existing.get() : builder.build(claim.ownerId(), claim.month(), now()).orElse(null);
                if (existing.isEmpty() && recap != null && recap.companion() != null
                        && recap.companion().avatarFileName() != null) {
                    String source = recap.companion().avatarFileName();
                    String target = java.util.UUID.randomUUID() + source.substring(source.lastIndexOf('.'));
                    if (!jobs.reserveImage(claim, target, now())) return;
                    images.copyProfile(source, target);
                    recap = recap.withCompanionAvatar(target);
                }
                completion.finish(claim, recap);
            } catch (RuntimeException exception) {
                jobs.retry(claim, now(), "GENERATION_FAILED");
                log.warn("Recap generation deferred. failureType={}", exception.getClass().getSimpleName());
            } finally {
                renewal.cancel(false);
            }
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
