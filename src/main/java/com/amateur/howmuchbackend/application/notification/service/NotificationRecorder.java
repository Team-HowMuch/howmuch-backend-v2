package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.SaveNotificationPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationRecorder {
    private final SaveNotificationPort savePort;
    private final SettlementNotificationOutboxPort outboxPort;
    @Transactional
    public boolean record(UserNotification notification) {
        if (!savePort.saveIfAbsent(notification)) return false;
        outboxPort.saveAll(List.of(SettlementNotificationOutbox.pending(notification.settlementId(),
                notification.recipientUserId(), notification.createdAt(), notification.id())));
        return true;
    }
}
