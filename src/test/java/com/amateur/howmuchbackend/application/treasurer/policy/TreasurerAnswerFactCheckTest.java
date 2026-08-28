package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerAnswerFactCheckTest {
    private final TreasurerAnswerFactCheck factCheck = new TreasurerAnswerFactCheck();

    @Test
    void acceptsKnownAmountsAndCalendarSuffixes() {
        TreasurerAnswerDraft draft = new TreasurerAnswerDraft(
                "이번 달 보낼 정산은 얼마야?",
                InquiryIntent.SEND_SETTLEMENTS,
                "현재 월 기준으로 조회했어요.",
                "현재 월 기준으로 조회했어요. 보낼 정산은 1건, 총 12,000원이에요.",
                12_000L,
                1,
                List.of()
        );

        assertThat(factCheck.accepts("보낼 정산이 1건, 12,000원이에요.", draft)).isTrue();
        assertThat(factCheck.accepts("보낼 정산이 3건, 99,000원이에요.", draft)).isFalse();
        assertThat(factCheck.accepts("8월 기준으로 보낼 정산이 1건, 12,000원이에요.", draft)).isTrue();
    }

    @Test
    void acceptsNumbersPresentInFallbackAnswer() {
        TreasurerAnswerDraft statusDraft = new TreasurerAnswerDraft(
                "정산 진행 상태는?",
                InquiryIntent.SETTLEMENT_STATUS,
                "이번 달 기준으로 조회했어요.",
                "이번 달 기준으로 조회했어요. 진행 중인 정산 1건, 완료된 정산 0건이에요.",
                12_000L,
                1,
                List.of()
        );

        assertThat(factCheck.accepts("진행 중인 정산 1건, 완료된 정산 0건이에요.", statusDraft)).isTrue();
    }
}
