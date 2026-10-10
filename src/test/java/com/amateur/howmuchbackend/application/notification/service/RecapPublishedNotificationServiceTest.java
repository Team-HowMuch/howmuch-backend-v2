package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.in.notification.command.RecapPublishedCommand;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecapPublishedNotificationServiceTest {
    private final BusinessNotificationService notifications = mock(BusinessNotificationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private final RecapPublishedNotificationService service = new RecapPublishedNotificationService(notifications, clock);
    private final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    @Test void publishedPastMonth_recordsNotification() {
        service.record(command(YearMonth.of(2026, 9), 1, now));
        verify(notifications).recapPublished(1L, YearMonth.of(2026, 9), now);
    }
    @Test void noCompletedSettlement_doesNotNotify() {
        service.record(command(YearMonth.of(2026, 9), 0, now));
        verifyNoInteractions(notifications);
    }
    @Test void currentOrFutureMonth_rejected() {
        for (YearMonth month : new YearMonth[]{YearMonth.of(2026, 10), YearMonth.of(2026, 11)})
            assertThatThrownBy(() -> service.record(command(month, 1, now))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(notifications);
    }
    @Test void futurePublication_rejected() {
        assertThatThrownBy(() -> service.record(command(YearMonth.of(2026, 9), 1, now.plusMinutes(6))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private RecapPublishedCommand command(YearMonth month, int count, LocalDateTime published) {
        return new RecapPublishedCommand(UUID.randomUUID(), 1L, month, count, published);
    }
}
