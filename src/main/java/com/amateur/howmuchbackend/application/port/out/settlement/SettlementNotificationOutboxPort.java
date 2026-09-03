package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;

import java.time.LocalDateTime;
import java.util.List;

public interface SettlementNotificationOutboxPort {
    void saveAll(List<SettlementNotificationOutbox> events);

    List<SettlementNotificationOutbox> claimReady(int batchSize, LocalDateTime now);

    int recoverStale(
            int batchSize,
            LocalDateTime staleBefore,
            LocalDateTime now,
            int maxAttempts
    );

    boolean transitionFromProcessing(
            SettlementNotificationOutbox event,
            LocalDateTime transitionedAt
    );
}
