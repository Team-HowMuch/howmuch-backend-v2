package com.amateur.howmuchbackend.domain.treasurer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public record TreasurerRecommendation(
        PersonalSettlement settlement,
        RecommendationPriority priority,
        String reason,
        List<TreasurerActionType> actions
) {
    public static List<TreasurerRecommendation> prioritize(List<PersonalSettlement> settlements, Instant now) {
        return settlements.stream()
                .filter(PersonalSettlement::isOpen)
                .map(settlement -> of(settlement, now))
                .sorted(Comparator.comparing(TreasurerRecommendation::priority))
                .toList();
    }

    private static TreasurerRecommendation of(PersonalSettlement settlement, Instant now) {
        boolean stale = settlement.isStale(now);
        if (settlement.isSend()) {
            return new TreasurerRecommendation(
                    settlement,
                    stale ? RecommendationPriority.SEND_STALE : RecommendationPriority.SEND_OPEN,
                    stale ? "오랫동안 처리되지 않은 정산이에요." : "보내야 할 정산이 아직 끝나지 않았어요.",
                    List.of(TreasurerActionType.SETTLEMENT_DETAIL, TreasurerActionType.TRANSFER)
            );
        }
        return new TreasurerRecommendation(
                settlement,
                stale ? RecommendationPriority.RECEIVE_STALE : RecommendationPriority.RECEIVE_OPEN,
                stale ? "오랫동안 처리되지 않은 정산이에요." : "받을 금액이 아직 남아 있어요.",
                List.of(TreasurerActionType.SETTLEMENT_DETAIL, TreasurerActionType.CHECK_DEPOSIT, TreasurerActionType.SEND_NOTIFICATION)
        );
    }
}
