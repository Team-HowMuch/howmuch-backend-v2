package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record PersonalSettlement(
        UUID settlementId,
        String title,
        SettlementDirection direction,
        SettlementStatus status,
        LocalDateTime requestedAt,
        Long amount,
        String counterpartName
) {
    public static final Duration STALE_AFTER = Duration.ofDays(7);

    public boolean isOpen() {
        return status != SettlementStatus.COMPLETED;
    }

    public long amountOrZero() {
        return amount == null ? 0L : amount;
    }

    public boolean isStale(Instant now) {
        if (!isOpen() || requestedAt == null) {
            return false;
        }
        return requestedAt.toInstant(java.time.ZoneOffset.UTC).isBefore(now.minus(STALE_AFTER));
    }

    public boolean isSend() {
        return direction == SettlementDirection.SEND;
    }

    public boolean isReceive() {
        return direction == SettlementDirection.RECEIVE;
    }
}
