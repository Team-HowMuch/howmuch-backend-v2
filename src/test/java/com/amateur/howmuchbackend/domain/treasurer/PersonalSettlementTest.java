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
    void countsOnlyIssuedSendSettlementsAsExpense() {
        assertThat(send(SettlementStatus.REQUESTED).countsAsExpense()).isTrue();
        assertThat(send(SettlementStatus.COMPLETED).countsAsExpense()).isTrue();
        assertThat(send(SettlementStatus.DRAFT).countsAsExpense()).isFalse();
        assertThat(settlement(SettlementStatus.REQUESTED).countsAsExpense()).isFalse();
    }

    @Test
    void doesNotMarkDraftOrCompletedAsStale() {
        LocalDateTime old = LocalDateTime.ofInstant(NOW.minus(PersonalSettlement.STALE_AFTER).minusSeconds(60), ZoneOffset.UTC);

        assertThat(settlement(SettlementStatus.REQUESTED, old).isStale(NOW)).isTrue();
        assertThat(settlement(SettlementStatus.DRAFT, old).isStale(NOW)).isFalse();
        assertThat(settlement(SettlementStatus.COMPLETED, old).isStale(NOW)).isFalse();
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
