package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
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
        String counterpartName,
        int payerCount,
        int completedPayerCount,
        SettlementAllocationType allocationType
) {
    public static final Duration STALE_AFTER = Duration.ofDays(7);
    /** 정산에 마감일이 없어 요청 후 하루가 지나면 기한을 넘긴 것으로 본다. */
    public static final Duration OVERDUE_AFTER = Duration.ofDays(1);

    public PersonalSettlement(
            UUID settlementId,
            String title,
            SettlementDirection direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            Long amount,
            String counterpartName
    ) {
        this(settlementId, title, direction, status, requestedAt, amount, counterpartName, 0, 0, null);
    }

    public PersonalSettlement(
            UUID settlementId,
            String title,
            SettlementDirection direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            Long amount,
            String counterpartName,
            int payerCount,
            int completedPayerCount
    ) {
        this(settlementId, title, direction, status, requestedAt, amount, counterpartName,
                payerCount, completedPayerCount, null);
    }

    public boolean isOpen() {
        return status != SettlementStatus.COMPLETED;
    }

    public boolean isOutstanding() {
        return status == SettlementStatus.REQUESTED
                || status == SettlementStatus.AWAITING_DEPOSIT
                || status == SettlementStatus.SUSPENDED;
    }

    public boolean countsAsExpense() {
        return isSend() && status != SettlementStatus.DRAFT;
    }

    public long amountOrZero() {
        return amount == null ? 0L : amount;
    }

    public boolean isStale(Instant now) {
        if (!isOutstanding() || requestedAt == null) {
            return false;
        }
        return requestedAt.toInstant(java.time.ZoneOffset.UTC).isBefore(now.minus(STALE_AFTER));
    }

    /** 중단된 정산은 사용자가 할 일이 없으므로 기한을 넘긴 것으로 보지 않는다. */
    public boolean isOverdue(Instant now) {
        if (status != SettlementStatus.REQUESTED && status != SettlementStatus.AWAITING_DEPOSIT) {
            return false;
        }
        return elapsedSinceRequest(now).compareTo(OVERDUE_AFTER) >= 0;
    }

    /** 요청 후 지난 시간(내림한 시간 단위). 요청 시각이 없거나 미래면 0이다. */
    public long elapsedHours(Instant now) {
        return elapsedSinceRequest(now).toHours();
    }

    private Duration elapsedSinceRequest(Instant now) {
        if (requestedAt == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(requestedAt.toInstant(java.time.ZoneOffset.UTC), now);
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    public boolean isSend() {
        return direction == SettlementDirection.SEND;
    }

    public boolean isReceive() {
        return direction == SettlementDirection.RECEIVE;
    }

    public int unpaidPayerCount() {
        int unpaid = payerCount - completedPayerCount;
        return Math.max(unpaid, 0);
    }
}
