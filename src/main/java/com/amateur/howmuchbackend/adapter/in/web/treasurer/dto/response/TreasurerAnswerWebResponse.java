package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;

import java.util.List;
import java.util.UUID;

public record TreasurerAnswerWebResponse(
        String intent,
        String period,
        String scope,
        String answer,
        List<Highlight> highlights,
        List<Action> actions
) {
    public static TreasurerAnswerWebResponse from(TreasurerAnswerResult result) {
        return new TreasurerAnswerWebResponse(
                result.intent(),
                result.period(),
                result.scope(),
                result.answer(),
                result.highlights().stream().map(Highlight::from).toList(),
                result.actions().stream().map(Action::from).toList()
        );
    }

    public record Highlight(
            UUID settlementId,
            String title,
            String direction,
            long amount
    ) {
        private static Highlight from(TreasurerAnswerResult.Highlight highlight) {
            return new Highlight(
                    highlight.settlementId(),
                    highlight.title(),
                    highlight.direction(),
                    highlight.amount()
            );
        }
    }

    public record Action(
            String type,
            String label
    ) {
        private static Action from(TreasurerAnswerResult.Action action) {
            return new Action(action.type(), action.label());
        }
    }
}
