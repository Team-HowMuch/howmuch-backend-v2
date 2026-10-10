package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotificationDeliveryServiceTest {
    @Test void preparationDatabaseFailure_doesNotClaimOrConsumeProviderBudget() {
        var store = mock(NotificationDeliveryStorePort.class);
        var content = mock(NotificationDeliveryContentResolver.class);
        var sender = mock(SettlementPushSenderPort.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var event = SettlementNotificationOutbox.pending(null, 1L, now, UUID.randomUUID());
        when(store.findReadyDelivery(event.id(), 0, now)).thenReturn(Optional.of(event));
        when(content.prepare(event, now)).thenThrow(new IllegalStateException("database unavailable"));
        var service = new NotificationDeliveryService(store, content, sender, new SettlementNotificationRetryPolicy(), clock, manager);
        assertThatThrownBy(() -> service.deliver(event.id(), 0)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).claimDelivery(any(), anyLong(), any());
        verify(store, never()).completeDelivery(any(), any(), any());
        verifyNoInteractions(sender);
    }
}
