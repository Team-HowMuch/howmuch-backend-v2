package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.time.LocalDateTime;
import java.util.*;

public interface NettingExecutionStorePort {
    enum Kind {
        CREATE,
        RECONCILE,
        DETECT
    }

    enum Status {
        QUEUED,
        SYNCING,
        RETRY,
        SUCCEEDED,
        STALE,
        FAILED
    }

    record Execution(
            UUID id,
            long groupId,
            UUID nettingId,
            long actorId,
            Kind kind,
            Status status,
            UUID leaseToken,
            String errorCode,
            LocalDateTime deadline) {}

    Execution enqueue(
            long groupId, UUID nettingId, long actorId, Kind kind, UUID key, LocalDateTime now);

    Optional<Execution> find(long groupId, UUID id);

    Optional<Execution> findByRequest(long groupId, long actorId, Kind kind, UUID key);

    Optional<Execution> findById(UUID id);

    Optional<Execution> claimNext(UUID workerId, LocalDateTime now);

    boolean heartbeat(UUID id, UUID leaseToken, UUID workerId, LocalDateTime now);

    void requireLease(UUID id, UUID leaseToken, LocalDateTime now);

    void finish(
            UUID id,
            UUID leaseToken,
            Status status,
            UUID nettingId,
            String error,
            LocalDateTime now);
}
