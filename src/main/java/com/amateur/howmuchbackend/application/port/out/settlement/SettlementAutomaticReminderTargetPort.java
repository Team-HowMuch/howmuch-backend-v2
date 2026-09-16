package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementAutomaticReminderTargetPort {
    List<Target> findDue(LocalDateTime automaticDueBefore, LocalDateTime lastReminderBefore, int limit);
    boolean hasRequestNotification(UUID settlementId, Long recipientUserId);
    Optional<LocalDateTime> findLatestAutomaticReminderAt(UUID shareId, Long recipientUserId);
    record Target(UUID settlementId, UUID participantId, UUID shareId, Long recipientUserId) {}
}
