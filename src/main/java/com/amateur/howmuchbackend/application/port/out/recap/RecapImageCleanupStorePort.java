package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.RecapImageCleanupClaim;
import java.time.LocalDateTime;
import java.util.Optional;

public interface RecapImageCleanupStorePort {
    void enqueue(String fileName, LocalDateTime now);
    boolean isReferenced(String fileName, LocalDateTime now);
    Optional<RecapImageCleanupClaim> claimNext(LocalDateTime now);
    void complete(RecapImageCleanupClaim claim);
    void retry(RecapImageCleanupClaim claim, LocalDateTime now, String failureCode);
}
