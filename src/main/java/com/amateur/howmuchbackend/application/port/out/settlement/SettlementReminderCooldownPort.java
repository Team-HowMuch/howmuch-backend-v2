package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.Duration;
import java.util.UUID;

public interface SettlementReminderCooldownPort {
    boolean reserve(
            UUID settlementId,
            UUID participantId,
            String reservationId,
            Duration cooldown
    );

    void release(UUID settlementId, UUID participantId, String reservationId);
}
