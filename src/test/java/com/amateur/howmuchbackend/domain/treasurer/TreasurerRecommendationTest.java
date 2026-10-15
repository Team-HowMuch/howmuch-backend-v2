package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerRecommendationTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @Test
    @DisplayName("보낼+기한 초과 → 보낼 → 받을+기한 초과 → 받을 순서다")
    void recommendations_fourTiersInOrder() {
        PersonalSettlement receiveOpen = settlement(SettlementDirection.RECEIVE, ago(Duration.ofHours(2)), 1);
        PersonalSettlement receiveOverdue = settlement(SettlementDirection.RECEIVE, ago(Duration.ofHours(30)), 2);
        PersonalSettlement sendOpen = settlement(SettlementDirection.SEND, ago(Duration.ofHours(2)), 3);
        PersonalSettlement sendOverdue = settlement(SettlementDirection.SEND, ago(Duration.ofHours(30)), 4);

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(
                List.of(receiveOpen, receiveOverdue, sendOpen, sendOverdue), NOW);

        assertThat(result).extracting(TreasurerRecommendation::priority).containsExactly(
                RecommendationPriority.SEND_OVERDUE,
                RecommendationPriority.SEND_OPEN,
                RecommendationPriority.RECEIVE_OVERDUE,
                RecommendationPriority.RECEIVE_OPEN);
        assertThat(result).extracting(TreasurerRecommendation::reason).containsExactly(
                "30시간 이상 지난 정산이에요. 빨리 정산해 주세요.",
                "보내야 할 정산이 아직 끝나지 않았어요.",
                "요청한 지 하루가 넘었는데 받을 금액이 남아 있어요.",
                "받을 금액이 아직 남아 있어요.");
        assertThat(result.getFirst().isUrgentSend()).isTrue();
        assertThat(result.get(1).isUrgentSend()).isFalse();
    }

    @Test
    @DisplayName("요청 후 정확히 24시간이면 기한 초과, 1초 모자라면 아니다")
    void recommendations_overdueBoundary() {
        PersonalSettlement justOverdue = settlement(SettlementDirection.SEND, ago(Duration.ofHours(24)), 1);
        PersonalSettlement almost = settlement(SettlementDirection.SEND, ago(Duration.ofHours(24).minusSeconds(1)), 1);

        assertThat(TreasurerRecommendation.prioritize(List.of(justOverdue), NOW).getFirst().reason())
                .isEqualTo("24시간 이상 지난 정산이에요. 빨리 정산해 주세요.");
        assertThat(TreasurerRecommendation.prioritize(List.of(almost), NOW).getFirst().priority())
                .isEqualTo(RecommendationPriority.SEND_OPEN);
    }

    @Test
    @DisplayName("7일이 넘은 정산은 기한 초과 단계에서 이유 문장으로 오래됐다고 알리고, 경과 시간은 시간 단위로만 쓴다")
    void recommendations_staleShownInReason() {
        PersonalSettlement staleSend = settlement(SettlementDirection.SEND, ago(Duration.ofDays(10)), 1);
        PersonalSettlement staleReceive = settlement(SettlementDirection.RECEIVE, ago(Duration.ofDays(10)), 1);

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(List.of(staleReceive, staleSend), NOW);

        assertThat(result).extracting(TreasurerRecommendation::priority).containsExactly(
                RecommendationPriority.SEND_OVERDUE, RecommendationPriority.RECEIVE_OVERDUE);
        assertThat(result).extracting(TreasurerRecommendation::reason).containsExactly(
                "240시간 이상 지난, 오랫동안 처리되지 않은 정산이에요. 빨리 정산해 주세요.",
                "오랫동안 처리되지 않은 정산이에요.");
    }

    @Test
    @DisplayName("같은 단계에서는 오래된 정산 → 금액이 큰 정산 순이고, 요청 시각이 없으면 뒤에 둔다")
    void recommendations_sameTierOrder() {
        PersonalSettlement older = settlement(SettlementDirection.SEND, ago(Duration.ofHours(50)), 1_000);
        PersonalSettlement newerSmall = settlement(SettlementDirection.SEND, ago(Duration.ofHours(30)), 1_000);
        PersonalSettlement newerLarge = settlement(SettlementDirection.SEND, ago(Duration.ofHours(30)), 9_000);
        PersonalSettlement fresh = settlement(SettlementDirection.SEND, ago(Duration.ofHours(1)), 5_000);
        PersonalSettlement noRequestedAt = settlement(SettlementDirection.SEND, null, 99_000);

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(
                List.of(noRequestedAt, fresh, newerSmall, newerLarge, older), NOW);

        assertThat(result).extracting(TreasurerRecommendation::settlement)
                .containsExactly(older, newerLarge, newerSmall, fresh, noRequestedAt);
    }

    @Test
    @DisplayName("중단된 정산은 오래돼도 기한 초과가 아니고 중단됐다고 알린다")
    void recommendations_suspendedNotOverdue() {
        PersonalSettlement suspendedSend = settlement(
                SettlementDirection.SEND, SettlementStatus.SUSPENDED, ago(Duration.ofDays(10)), 1);
        PersonalSettlement suspendedReceive = settlement(
                SettlementDirection.RECEIVE, SettlementStatus.SUSPENDED, ago(Duration.ofDays(10)), 1);
        PersonalSettlement freshReceive = settlement(SettlementDirection.RECEIVE, ago(Duration.ofHours(1)), 1);

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(
                List.of(freshReceive, suspendedReceive, suspendedSend), NOW);

        assertThat(result).extracting(TreasurerRecommendation::priority).containsExactly(
                RecommendationPriority.SEND_OPEN, RecommendationPriority.RECEIVE_OPEN, RecommendationPriority.RECEIVE_OPEN);
        assertThat(result.getFirst().reason()).isEqualTo("중단된 정산이에요.");
        assertThat(result.get(1).settlement()).isEqualTo(suspendedReceive);
        assertThat(result.get(1).reason()).isEqualTo("중단된 정산이에요.");
    }

    @Test
    void recommendations_completedSettlement_ignored() {
        PersonalSettlement completed = new PersonalSettlement(
                UUID.randomUUID(),
                "끝",
                SettlementDirection.SEND,
                SettlementStatus.COMPLETED,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                1L,
                null
        );

        assertThat(TreasurerRecommendation.prioritize(List.of(completed), NOW)).isEmpty();
    }

    @Test
    void recommendations_draftSettlement_ignored() {
        PersonalSettlement draft = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.DRAFT,
                ago(Duration.ofSeconds(60)),
                1
        );

        assertThat(TreasurerRecommendation.prioritize(List.of(draft), NOW)).isEmpty();
    }

    @Test
    void recommendation_sendAwaitingDeposit_transferExposed() {
        PersonalSettlement requested = settlement(
                SettlementDirection.SEND,
                SettlementStatus.REQUESTED,
                ago(Duration.ofSeconds(60)),
                1
        );
        PersonalSettlement awaiting = settlement(
                SettlementDirection.SEND,
                SettlementStatus.AWAITING_DEPOSIT,
                ago(Duration.ofSeconds(60)),
                2
        );

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(List.of(requested, awaiting), NOW);

        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.REQUESTED)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL));
        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.AWAITING_DEPOSIT)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL, TreasurerActionType.TRANSFER));
    }

    @Test
    void recommendation_receiveAwaitingDeposit_depositCheckExposed() {
        PersonalSettlement requested = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.REQUESTED,
                ago(Duration.ofSeconds(60)),
                1
        );
        PersonalSettlement awaiting = settlement(
                SettlementDirection.RECEIVE,
                SettlementStatus.AWAITING_DEPOSIT,
                ago(Duration.ofSeconds(60)),
                2
        );

        List<TreasurerRecommendation> result = TreasurerRecommendation.prioritize(List.of(requested, awaiting), NOW);

        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.REQUESTED)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL));
        assertThat(result).filteredOn(item -> item.settlement().status() == SettlementStatus.AWAITING_DEPOSIT)
                .singleElement()
                .extracting(TreasurerRecommendation::actions)
                .isEqualTo(List.of(TreasurerActionType.SETTLEMENT_DETAIL, TreasurerActionType.CHECK_DEPOSIT));
    }

    private static LocalDateTime ago(Duration duration) {
        return LocalDateTime.ofInstant(NOW.minus(duration), ZoneOffset.UTC);
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
