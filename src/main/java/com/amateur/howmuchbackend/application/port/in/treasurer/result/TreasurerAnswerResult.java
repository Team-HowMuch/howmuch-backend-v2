package com.amateur.howmuchbackend.application.port.in.treasurer.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerCardMarker;

import java.util.List;
import java.util.UUID;

public record TreasurerAnswerResult(
        UUID answerId,
        InquiryIntent intent,
        InquiryPeriod period,
        String scope,
        TreasurerAnswerView view,
        String answer,
        String footer,
        List<Item> items,
        SummaryCard summary,
        List<Action> actions,
        List<Prompt> prompts
) {
    /**
     * 저장된 답변 식별자를 붙인 사본. 저장에 실패하면 {@code null}이고 앱은 신고 버튼을 숨긴다.
     */
    public TreasurerAnswerResult withAnswerId(UUID savedAnswerId) {
        return new TreasurerAnswerResult(
                savedAnswerId, intent, period, scope, view, answer, footer, items, summary, actions, prompts);
    }

    public TreasurerAnswerResult withAnswer(String newAnswer) {
        return new TreasurerAnswerResult(
                answerId, intent, period, scope, view, newAnswer, footer, items, summary, actions, prompts);
    }

    public record Item(
            UUID settlementId,
            String requestedDate,
            String headline,
            String subtitle,
            SettlementDirection direction,
            long amount,
            TreasurerCardMarker marker,
            SettlementAllocationType allocationType,
            SettlementStatus status
    ) {
    }

    public record SummaryCard(
            SummaryRow send,
            SummaryRow receive,
            SummaryRow completed
    ) {
    }

    public record SummaryRow(
            String label,
            String detail,
            String value
    ) {
    }

    public record Action(
            TreasurerAnswerActionType type,
            String label
    ) {
    }

    public record Prompt(
            InquiryIntent type,
            String label,
            String question
    ) {
    }
}
