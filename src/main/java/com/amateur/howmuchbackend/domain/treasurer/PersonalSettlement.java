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
