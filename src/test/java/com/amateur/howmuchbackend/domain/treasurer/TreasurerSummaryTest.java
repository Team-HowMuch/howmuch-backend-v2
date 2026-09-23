package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerSummaryTest {
    @Test
    void treasurerSummary_sendGuidance_preferred() {
        TreasurerSummary summary = TreasurerSummary.from(List.of(
                settlement(SettlementDirection.SEND, SettlementStatus.REQUESTED, 30_000L),
                settlement(SettlementDirection.RECEIVE, SettlementStatus.AWAITING_DEPOSIT, 50_000L)
        ));

        assertThat(summary.sendCount()).isEqualTo(1);
        assertThat(summary.sendAmount()).isEqualTo(30_000L);
        assertThat(summary.receiveCount()).isEqualTo(1);
        assertThat(summary.inProgressCount()).isEqualTo(2);
        assertThat(summary.message()).isEqualTo("보낼 정산 1건, 30,000원이 남아 있어요.");
    }

    @Test
    void usesEmptyGuidanceWhenNothingIsOpen() {
        TreasurerSummary summary = TreasurerSummary.from(List.of(
                settlement(SettlementDirection.SEND, SettlementStatus.COMPLETED, 10_000L)
        ));

        assertThat(summary.completedCount()).isEqualTo(1);
        assertThat(summary.inProgressCount()).isZero();
        assertThat(summary.message()).isEqualTo("현재 처리할 정산이 없어요.");
    }

    @Test
    void countsDraftAsInProgressWithoutSendOrReceiveBalance() {
        TreasurerSummary summary = TreasurerSummary.from(List.of(
                settlement(SettlementDirection.RECEIVE, SettlementStatus.DRAFT, 80_000L)
        ));

        assertThat(summary.inProgressCount()).isEqualTo(1);
        assertThat(summary.receiveCount()).isZero();
        assertThat(summary.receiveAmount()).isZero();
        assertThat(summary.message()).isEqualTo("진행 중인 정산 1건이 있어요.");
    }

    private PersonalSettlement settlement(SettlementDirection direction, SettlementStatus status, long amount) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                "여행 모임",
                direction,
                status,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                amount,
                "요청자"
        );
    }
}
