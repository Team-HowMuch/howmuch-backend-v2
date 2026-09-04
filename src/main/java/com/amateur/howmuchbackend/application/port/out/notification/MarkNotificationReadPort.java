package com.amateur.howmuchbackend.application.port.out.notification;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface MarkNotificationReadPort {
    Optional<LocalDateTime> markRead(UUID id, Long recipientUserId, LocalDateTime now);
}
