package com.amateur.howmuchbackend.domain.treasurer;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record TreasurerRecommendation(
        PersonalSettlement settlement,
        RecommendationPriority priority,
        String reason,
        List<TreasurerActionType> actions
) {
    private static final Comparator<TreasurerRecommendation> ORDER =
            Comparator.comparing(TreasurerRecommendation::priority)
                    .thenComparing(recommendation -> recommendation.settlement().requestedAt(),
                            Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                    .thenComparing(recommendation -> recommendation.settlement().amountOrZero(),
                            Comparator.reverseOrder())
                    .thenComparing(recommendation -> recommendation.settlement().settlementId());

    /**
     * 끝나지 않은 정산을 보낼 + 기한 초과 → 보낼 → 받을 + 기한 초과 → 받을 순서로 놓는다.
     * 같은 단계 안에서는 오래된 정산, 금액이 큰 정산이 먼저다.
     */
    public static List<TreasurerRecommendation> prioritize(List<PersonalSettlement> settlements, Instant now) {
        return settlements.stream()
                .filter(PersonalSettlement::isOutstanding)
                .map(settlement -> of(settlement, now))
                .sorted(ORDER)
                .toList();
    }

    public boolean isUrgentSend() {
        return priority == RecommendationPriority.SEND_OVERDUE;
    }

    private static TreasurerRecommendation of(PersonalSettlement settlement, Instant now) {
        boolean overdue = settlement.isOverdue(now);
        RecommendationPriority priority = settlement.isSend()
                ? overdue ? RecommendationPriority.SEND_OVERDUE : RecommendationPriority.SEND_OPEN
                : overdue ? RecommendationPriority.RECEIVE_OVERDUE : RecommendationPriority.RECEIVE_OPEN;
        return new TreasurerRecommendation(settlement, priority, reasonOf(settlement, priority, now),
                actionsOf(settlement));
    }

    private static String reasonOf(PersonalSettlement settlement, RecommendationPriority priority, Instant now) {
        boolean stale = settlement.isStale(now);
        return switch (priority) {
            case SEND_OVERDUE -> "%d시간 이상 지난%s 정산이에요. 빨리 정산해 주세요."
                    .formatted(settlement.elapsedHours(now), stale ? ", 오랫동안 처리되지 않은" : "");
            case RECEIVE_OVERDUE -> stale
                    ? "오랫동안 처리되지 않은 정산이에요."
                    : "요청한 지 하루가 넘었는데 받을 금액이 남아 있어요.";
            case SEND_OPEN -> settlement.status() == SettlementStatus.SUSPENDED
                    ? "중단된 정산이에요."
                    : "보내야 할 정산이 아직 끝나지 않았어요.";
            case RECEIVE_OPEN -> settlement.status() == SettlementStatus.SUSPENDED
                    ? "중단된 정산이에요."
                    : "받을 금액이 아직 남아 있어요.";
        };
    }

    static List<TreasurerActionType> actionsOf(PersonalSettlement settlement) {
        List<TreasurerActionType> actions = new ArrayList<>();
        actions.add(TreasurerActionType.SETTLEMENT_DETAIL);
        if (settlement.status() == SettlementStatus.AWAITING_DEPOSIT) {
            if (settlement.isSend()) {
                actions.add(TreasurerActionType.TRANSFER);
            } else {
                actions.add(TreasurerActionType.CHECK_DEPOSIT);
            }
        }
        return List.copyOf(actions);
    }
}
