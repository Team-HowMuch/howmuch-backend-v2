package com.amateur.howmuchbackend.application.port.in.treasurer.result;

import java.util.List;
import java.util.UUID;

public record TreasurerRecommendationsResult(
        String message,
        List<Item> items
) {
    public record Item(
            UUID settlementId,
            String title,
            String direction,
            String priority,
            String reason,
            long amount,
            List<Action> actions
    ) {
    }

    public record Action(
            String type,
            String label
    ) {
    }
}
