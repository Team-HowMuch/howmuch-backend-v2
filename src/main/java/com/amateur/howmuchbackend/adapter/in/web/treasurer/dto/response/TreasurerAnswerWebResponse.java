package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;

import java.util.List;
import java.util.UUID;

public record TreasurerAnswerWebResponse(
        String intent,
        String period,
        String scope,
        String view,
        String answer,
        String footer,
        List<Item> items,
        SummaryCard summary,
        List<Action> actions,
        List<Prompt> prompts
) {
    public static TreasurerAnswerWebResponse from(TreasurerAnswerResult result) {
        return new TreasurerAnswerWebResponse(
                result.intent().name(),
                result.period() == null ? null : result.period().name(),
                result.scope(),
                result.view().name(),
                result.answer(),
                result.footer(),
                result.items() == null ? List.of() : result.items().stream().map(Item::from).toList(),
                result.summary() == null ? null : SummaryCard.from(result.summary()),
                result.actions() == null ? List.of() : result.actions().stream().map(Action::from).toList(),
                result.prompts() == null ? List.of() : result.prompts().stream().map(Prompt::from).toList()
        );
    }

    public record Item(
            UUID settlementId,
            String requestedDate,
            String headline,
            String subtitle,
            String direction,
            long amount,
            String marker
    ) {
        private static Item from(TreasurerAnswerResult.Item item) {
            return new Item(
                    item.settlementId(),
                    item.requestedDate(),
                    item.headline(),
                    item.subtitle(),
                    item.direction().name(),
                    item.amount(),
                    item.marker().name()
            );
        }
    }

    public record SummaryCard(
            SummaryRow send,
            SummaryRow receive,
            SummaryRow completed
    ) {
        private static SummaryCard from(TreasurerAnswerResult.SummaryCard summary) {
            return new SummaryCard(
                    SummaryRow.from(summary.send()),
                    SummaryRow.from(summary.receive()),
                    SummaryRow.from(summary.completed())
            );
        }
    }

    public record SummaryRow(
            String label,
            String detail,
            String value
    ) {
        private static SummaryRow from(TreasurerAnswerResult.SummaryRow row) {
            return new SummaryRow(row.label(), row.detail(), row.value());
        }
    }

    public record Action(
            String type,
            String label
    ) {
        private static Action from(TreasurerAnswerResult.Action action) {
            return new Action(action.type().name(), action.label());
        }
    }

    public record Prompt(
            String type,
            String label,
            String question
    ) {
        private static Prompt from(TreasurerAnswerResult.Prompt prompt) {
            return new Prompt(prompt.type().name(), prompt.label(), prompt.question());
        }
    }
}
