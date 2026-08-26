package com.amateur.howmuchbackend.application.port.in.treasurer.response;

import java.util.List;
import java.util.UUID;

public record TreasurerAnswerResponse(
        String intent,
        String period,
        String scope,
        String answer,
        List<Highlight> highlights,
        List<Action> actions
) {
    public record Highlight(
            UUID settlementId,
            String title,
            String direction,
            long amount
    ) {
    }

    public record Action(
            String type,
            String label
    ) {
    }
}
