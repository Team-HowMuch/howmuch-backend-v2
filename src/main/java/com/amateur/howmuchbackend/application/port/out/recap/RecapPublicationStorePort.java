package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.RecapPublicationClaim;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RecapPublicationStorePort {
    void enqueue(UUID recapId, LocalDateTime now);
    int recoverExpired(LocalDateTime now, int limit);
    Optional<RecapPublicationClaim> claimNext(LocalDateTime now, LocalDateTime leaseUntil);
    boolean published(RecapPublicationClaim claim, LocalDateTime now);
    boolean retry(RecapPublicationClaim claim, LocalDateTime now, LocalDateTime nextAttemptAt);
}
