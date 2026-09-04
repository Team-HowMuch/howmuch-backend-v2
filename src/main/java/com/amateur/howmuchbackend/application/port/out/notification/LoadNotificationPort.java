package com.amateur.howmuchbackend.application.port.out.notification;

import com.amateur.howmuchbackend.domain.notification.UserNotification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoadNotificationPort {
    List<UserNotification> findPage(Long recipientUserId, LocalDateTime beforeAt, UUID beforeId, int limit);
    Optional<UserNotification> findById(UUID id);
    long countUnread(Long recipientUserId);
}
