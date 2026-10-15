package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalSettlementTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    void personalSettlement_requestedAwaitingOrSuspended_outstanding() {
        assertThat(settlement(SettlementStatus.REQUESTED).isOutstanding()).isTrue();
        assertThat(settlement(SettlementStatus.AWAITING_DEPOSIT).isOutstanding()).isTrue();
        assertThat(settlement(SettlementStatus.SUSPENDED).isOutstanding()).isTrue();
        assertThat(settlement(SettlementStatus.DRAFT).isOutstanding()).isFalse();
        assertThat(settlement(SettlementStatus.COMPLETED).isOutstanding()).isFalse();
    }

    @Test
    void personalSettlement_issuedSendOnly_countsAsExpense() {
        assertThat(send(SettlementStatus.REQUESTED).countsAsExpense()).isTrue();
        assertThat(send(SettlementStatus.COMPLETED).countsAsExpense()).isTrue();
        assertThat(send(SettlementStatus.DRAFT).countsAsExpense()).isFalse();
        assertThat(settlement(SettlementStatus.REQUESTED).countsAsExpense()).isFalse();
    }

    @Test
    void personalSettlement_draftOrCompleted_notStale() {
        LocalDateTime old = LocalDateTime.ofInstant(NOW.minus(PersonalSettlement.STALE_AFTER).minusSeconds(60), ZoneOffset.UTC);

        assertThat(settlement(SettlementStatus.REQUESTED, old).isStale(NOW)).isTrue();
        assertThat(settlement(SettlementStatus.DRAFT, old).isStale(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.COMPLETED, old).isStale(NOW)).isFalse();
    }

    @Test
    void personalSettlement_oneDayAfterRequest_overdue() {
        LocalDateTime oneDay = LocalDateTime.ofInstant(NOW.minus(PersonalSettlement.OVERDUE_AFTER), ZoneOffset.UTC);
        LocalDateTime almost = oneDay.plusSeconds(1);

        assertThat(settlement(SettlementStatus.REQUESTED, oneDay).isOverdue(NOW)).isTrue();
        assertThat(settlement(SettlementStatus.AWAITING_DEPOSIT, oneDay).isOverdue(NOW)).isTrue();
        assertThat(settlement(SettlementStatus.REQUESTED, almost).isOverdue(NOW)).isFalse();
    }

    @Test
    void personalSettlement_notActiveOrNoRequestTime_notOverdue() {
        LocalDateTime old = LocalDateTime.of(2026, 8, 1, 0, 0);

        assertThat(settlement(SettlementStatus.SUSPENDED, old).isOverdue(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.DRAFT, old).isOverdue(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.COMPLETED, old).isOverdue(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.REQUESTED, null).isOverdue(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.REQUESTED, LocalDateTime.of(2026, 9, 1, 0, 0)).isOverdue(NOW)).isFalse();
    }

    @Test
    void personalSettlement_elapsedHours_floored() {
        LocalDateTime requestedAt = LocalDateTime.ofInstant(NOW.minusSeconds(25 * 3600 - 1), ZoneOffset.UTC);

        assertThat(settlement(SettlementStatus.REQUESTED, requestedAt).elapsedHours(NOW)).isEqualTo(24);
        assertThat(settlement(SettlementStatus.REQUESTED, null).elapsedHours(NOW)).isZero();
        assertThat(settlement(SettlementStatus.REQUESTED, LocalDateTime.of(2026, 9, 1, 0, 0)).elapsedHours(NOW)).isZero();
    }

    private PersonalSettlement settlement(SettlementStatus status) {
        return settlement(status, LocalDateTime.of(2026, 8, 10, 0, 0));
    }

    private PersonalSettlement settlement(SettlementStatus status, LocalDateTime requestedAt) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                "여행 모임",
                SettlementDirection.RECEIVE,
                status,
                requestedAt,
                10_000L,
                "나"
        );
    }

    private PersonalSettlement send(SettlementStatus status) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                "회식 정산",
                SettlementDirection.SEND,
                status,
                LocalDateTime.of(2026, 8, 10, 0, 0),
                12_000L,
                "요청자"
        );
    }
}
