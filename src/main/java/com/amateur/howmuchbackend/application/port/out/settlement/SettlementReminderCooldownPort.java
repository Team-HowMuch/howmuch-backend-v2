package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface SettlementReminderCooldownPort {
    boolean reserve(
            UUID settlementId,
            UUID participantId,
            String reservationId,
            Duration cooldown
    );

    void release(UUID settlementId, UUID participantId, String reservationId);

    Optional<Duration> findRemainingCooldown(UUID settlementId, UUID participantId);
}
