package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.in.notification.RecordRecapPublishedNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.command.RecapPublishedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class RecapPublishedNotificationService implements RecordRecapPublishedNotificationUseCase {
    private final BusinessNotificationService notifications;
    private final Clock clock;

    @Override
    @Transactional
    public void record(RecapPublishedCommand command) {
        YearMonth currentMonth = YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Seoul")));
        if (!command.yearMonth().isBefore(currentMonth))
            throw new IllegalArgumentException("공개된 리캡은 지난 월이어야 합니다.");
        if (command.publishedAt().isAfter(LocalDateTime.now(clock).plusMinutes(5)))
            throw new IllegalArgumentException("리캡 공개 시각이 미래입니다.");
        if (command.completedSettlementCount() == 0) return;
        notifications.recapPublished(command.userId(), command.yearMonth(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
