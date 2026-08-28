package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerRecommendationsResult;

import java.util.List;
import java.util.UUID;

public record TreasurerRecommendationsWebResponse(
        String message,
        List<Item> items
) {
    public static TreasurerRecommendationsWebResponse from(TreasurerRecommendationsResult result) {
        return new TreasurerRecommendationsWebResponse(
                result.message(),
                result.items().stream().map(Item::from).toList()
        );
    }

    public record Item(
            UUID settlementId,
            String title,
            String direction,
            String priority,
            String reason,
            long amount,
            List<Action> actions
    ) {
        private static Item from(TreasurerRecommendationsResult.Item item) {
            return new Item(
                    item.settlementId(),
                    item.title(),
                    item.direction(),
                    item.priority(),
                    item.reason(),
                    item.amount(),
                    item.actions().stream().map(Action::from).toList()
            );
        }
    }

    public record Action(
            String type,
            String label
    ) {
        private static Action from(TreasurerRecommendationsResult.Action action) {
            return new Action(action.type(), action.label());
        }
    }
}
