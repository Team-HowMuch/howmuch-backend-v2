package com.amateur.howmuchbackend.application.port.in.notification.command;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

public record RecapPublishedCommand(UUID messageId, Long userId, YearMonth yearMonth,
        int completedSettlementCount, LocalDateTime publishedAt) {
    public RecapPublishedCommand {
        Objects.requireNonNull(messageId);
        Objects.requireNonNull(yearMonth);
        Objects.requireNonNull(publishedAt);
        if (userId == null || userId <= 0 || completedSettlementCount < 0) {
            throw new IllegalArgumentException("리캡 공개 정보가 유효하지 않습니다.");
        }
    }
}
