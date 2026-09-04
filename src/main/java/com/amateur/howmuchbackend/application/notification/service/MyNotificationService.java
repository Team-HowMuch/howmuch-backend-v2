package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.in.notification.*;
import com.amateur.howmuchbackend.application.port.in.notification.command.GetMyNotificationsCommand;
import com.amateur.howmuchbackend.application.port.in.notification.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.notification.LoadNotificationPort;
import com.amateur.howmuchbackend.application.port.out.notification.MarkNotificationReadPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.domain.notification.status.NotificationStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MyNotificationService implements GetMyNotificationsUseCase, CountMyUnreadNotificationsUseCase, ReadMyNotificationUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadNotificationPort loadPort;
    private final MarkNotificationReadPort readPort;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public NotificationPageResult get(GetMyNotificationsCommand command) {
        Long userId = currentActorPort.getCurrentActor().userId();
        int size = command.size() == null ? 20 : command.size();
        if (size < 1 || size > 50) throw ApplicationException.of(NotificationStatusCode.INVALID_NOTIFICATION_PAGE_SIZE);
        NotificationCursor cursor = NotificationCursor.decode(command.cursor());
        var fetched = loadPort.findPage(userId, cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), size + 1);
        boolean hasNext = fetched.size() > size;
        var page = fetched.subList(0, Math.min(size, fetched.size()));
        String next = hasNext ? new NotificationCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null;
        return new NotificationPageResult(page.stream().map(this::toResult).toList(), next, hasNext);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadNotificationCountResult countUnread() {
        return new UnreadNotificationCountResult(loadPort.countUnread(currentActorPort.getCurrentActor().userId()));
    }

    @Override
    @Transactional
    public NotificationReadResult read(UUID id) {
        LocalDateTime readAt = readPort.markRead(id, currentActorPort.getCurrentActor().userId(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .orElseThrow(() -> ApplicationException.of(NotificationStatusCode.NOTIFICATION_NOT_FOUND));
        return new NotificationReadResult(id, readAt);
    }

    private NotificationResult toResult(UserNotification n) {
        String label = n.type() == NotificationType.SETTLEMENT_REMINDER ? "지금 바로 정산하러 가기"
                : n.allocationType() == SettlementAllocationType.ITEM_ALLOCATION ? "영수증 정산하러 가기" : "정산하러 가기";
        return new NotificationResult(n.id(), n.type(), n.allocationType(), n.title(), n.message(), n.createdAt(),
                n.readAt(), n.settlementId(), new NotificationResult.Action("SETTLEMENT_DETAIL", label, n.settlementId() != null));
    }
}
