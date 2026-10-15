package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.notification.command.GetMyNotificationsCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.notification.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.notification.*;
import com.amateur.howmuchbackend.domain.notification.status.NotificationStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyNotificationServiceTest {
    @Mock CurrentActorPort actor;
    @Mock LoadNotificationPort load;
    @Mock MarkNotificationReadPort read;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    private MyNotificationService service;
    @BeforeEach void setUp() {
        service = new MyNotificationService(actor, load, read, clock);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(2L));
    }
    @Test void notificationList_pageRequest_doesNotMarkRead() {
        var first = notification(UUID.randomUUID());
        when(load.findPage(2L,null,null,2)).thenReturn(List.of(first, notification(null)));
        var result = service.get(new GetMyNotificationsCommand(null,1));
        assertThat(result.items()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        var cursor = NotificationCursor.decode(result.nextCursor());
        assertThat(cursor.createdAt()).isEqualTo(first.createdAt());
        assertThat(cursor.id()).isEqualTo(first.id());
        when(load.findPage(2L,first.createdAt(),first.id(),2)).thenReturn(List.of(notification(null)));
        var last = service.get(new GetMyNotificationsCommand(result.nextCursor(),1));
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(last.items().getFirst().action().enabled()).isFalse();
        verifyNoInteractions(read);
    }
    @Test void notificationList_emptyPage_returnsEmptyAndCount() {
        when(load.findPage(2L,null,null,21)).thenReturn(List.of());
        assertThat(service.get(new GetMyNotificationsCommand(null,null)).items()).isEmpty();
        when(load.countUnread(2L)).thenReturn(4L);
        assertThat(service.countUnread().count()).isEqualTo(4);
    }
    @Test void genericNotification_exposesTypedTargetWithoutSettlementAllocation() {
        var notification = new UserNotification(UUID.randomUUID(), 2L, null, NotificationType.MONTHLY_RECAP,
                null, "HowMuch", "월간 리캡", "지난달 내역", "recap-key", null, now,
                new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09"));
        when(load.findPage(2L, null, null, 21)).thenReturn(List.of(notification));
        var result = service.get(new GetMyNotificationsCommand(null, null)).items().getFirst();
        assertThat(result.allocationType()).isNull();
        assertThat(result.settlementId()).isNull();
        assertThat(result.action().type()).isEqualTo("MONTHLY_RECAP");
        assertThat(result.action().targetId()).isEqualTo("2026-09");
        assertThat(result.action().enabled()).isTrue();
    }
    @Test void notification_read_usesFirstReadTimestamp() {
        UUID id=UUID.randomUUID();
        when(read.markRead(id,2L,now)).thenReturn(Optional.of(now.minusHours(1)));
        assertThat(service.read(id).readAt()).isEqualTo(now.minusHours(1));
    }
    @Test void notificationLookup_missingOrForeign_returnsSame404() {
        UUID id=UUID.randomUUID();
        when(read.markRead(id,2L,now)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(id)).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(NotificationStatusCode.NOTIFICATION_NOT_FOUND));
    }
    @ParameterizedTest @ValueSource(strings={"", "broken!", "dGVzdA"})
    void notificationCursor_invalid_rejected(String cursor) {
        assertThatThrownBy(() -> service.get(new GetMyNotificationsCommand(cursor,20)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(NotificationStatusCode.INVALID_NOTIFICATION_CURSOR));
        verifyNoInteractions(load,read);
    }
    @ParameterizedTest @ValueSource(ints={0,51,-1})
    void notificationSize_invalid_rejected(int size) {
        assertThatThrownBy(() -> service.get(new GetMyNotificationsCommand(null,size)))
                .isInstanceOf(ApplicationException.class);
    }
    private UserNotification notification(UUID settlementId) {
        return new UserNotification(UUID.randomUUID(),2L,settlementId,NotificationType.SETTLEMENT_REQUEST,
                SettlementAllocationType.ITEM_ALLOCATION,"요청자","정산 요청","요청했어요","request-key",null,now);
    }
}
