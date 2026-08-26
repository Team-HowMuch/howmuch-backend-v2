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
    void ordersSendBeforeReceiveAndStaleBeforeFresh() {
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
    void ignoresCompletedSettlements() {
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

    private PersonalSettlement settlement(SettlementDirection direction, LocalDateTime requestedAt, long amount) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                "정산",
                direction,
                SettlementStatus.REQUESTED,
                requestedAt,
                amount,
                null
        );
    }
}
