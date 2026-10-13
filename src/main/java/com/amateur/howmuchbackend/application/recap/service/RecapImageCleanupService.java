package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.in.recap.CleanRecapImagesUseCase;
import com.amateur.howmuchbackend.application.port.out.recap.RecapImageCleanupStorePort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapImageStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecapImageCleanupService implements CleanRecapImagesUseCase {
    private final RecapImageCleanupStorePort cleanup;
    private final RecapImageStoragePort images;

    @Override
    public int cleanBatch(int limit) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        for (String file : images.findOlderThan(now.minusHours(24))) {
            if (!cleanup.isReferenced(file, now)) cleanup.enqueue(file, now);
        }
        int count = 0;
        while (count < limit) {
            var claimed = cleanup.claimNext(LocalDateTime.now(ZoneOffset.UTC));
            if (claimed.isEmpty()) break;
            var claim = claimed.get();
            try {
                if (cleanup.isReferenced(claim.fileName(), LocalDateTime.now(ZoneOffset.UTC))) {
                    cleanup.retry(claim, now, "IMAGE_REFERENCED");
                } else {
                    images.delete(claim.fileName());
                    cleanup.complete(claim);
                }
            } catch (RuntimeException exception) {
                cleanup.retry(claim, LocalDateTime.now(ZoneOffset.UTC), "IMAGE_DELETE_FAILED");
                log.warn("Recap image cleanup deferred. failureType={}", exception.getClass().getSimpleName());
            }
            count++;
        }
        return count;
    }
}
