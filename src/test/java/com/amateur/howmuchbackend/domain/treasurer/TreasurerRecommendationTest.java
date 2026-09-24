package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerRecommendationTest {
    @Test
    void recommendations_sendBeforeReceiveAndStaleBeforeFresh() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement staleSend = settlement(
                SettlementDirection.SEND,
                LocalDateTime.ofInstant(now.minus(PersonalSettlement.STALE_AFTER).minusSeconds(60), ZoneOffset.UTC),
                1
        );
        PersonalSettlement freshReceive = settlement(
                SettlementDirection.RECEIVE,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                2
        );

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(
                List.of(freshReceive, staleSend),
                now
        );

        assertThat(result).extracting(item -> item.priority().name())
                .containsExactly("SEND_STALE", "RECEIVE_OPEN");
        assertThat(result.getFirst().reason()).isEqualTo("오랫동안 처리되지 않은 정산이에요.");
    }

    @Test
    void recommendations_completedSettlement_ignored() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement completed = new PersonalSettlement(
                UUID.randomUUID(),
                "끝",
                SettlementDirection.SEND,
                SettlementStatus.COMPLETED,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                1L,
                null
        );

        assertThat(TreasurerRecommendation.prioritize(List.of(completed), now)).isEmpty();
    }

    @Test
    void recommendations_draftSettlement_ignored() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement draft = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.DRAFT,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                1
        );

        assertThat(TreasurerRecommendation.prioritize(List.of(draft), now)).isEmpty();
    }

    @Test
    void recommendation_sendAwaitingDeposit_transferExposed() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement requested = settlement(
                SettlementDirection.SEND,
                SettlementStatus.REQUESTED,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                1
        );
        PersonalSettlement awaiting = settlement(
                SettlementDirection.SEND,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                2
        );

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(List.of(requested, awaiting), now);

        assertThat(result).satisfiesExactly(
                item -> assertThat(item.actions()).containsExactly(TreasurerActionType.SETTLEMENT_DETAIL),
                item -> assertThat(item.actions()).containsExactly(
                        TreasurerActionType.SETTLEMENT_DETAIL,
                        TreasurerActionType.TRANSFER
                )
        );
    }

    @Test
    void recommendation_receiveAwaitingDeposit_depositCheckExposed() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement requested = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.REQUESTED,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                1
        );
        PersonalSettlement awaiting = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC),
                2
        );

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(List.of(requested, awaiting), now);

        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.REQUESTED)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL));
        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.AWAITING_DEPOSIT)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL, TreasurerActionType.CHECK_DEPOSIT));
    }

    private PersonalSettlement settlement(SettlementDirection direction, LocalDateTime requestedAt, long amount) {
        return settlement(direction, SettlementStatus.REQUESTED, requestedAt, amount);
    }

    private PersonalSettlement settlement(
            SettlementDirection direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            long amount
    ) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                "정산",
                direction,
                status,
                requestedAt,
                amount,
                null
        );
    }
}
