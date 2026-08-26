package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryParserTest {
    private final InquiryParser parser = new InquiryParser();

    @Test
    void classifiesSendIntentAndThisMonth() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 내가 보낼 정산은 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
    }

    @Test
    void extractsTitleHintForGroupName() {
        InquiryParser.ParsedInquiry parsed = parser.parse("여행 모임에서 받을 돈이 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void extractsSettlementTitleWithoutQuestionRemainder() {
        InquiryParser.ParsedInquiry parsed = parser.parse("회식 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("회식정산");
    }

    @Test
    void doesNotTreatSendSettlementGrammarAsTitle() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 내가 보낼 정산은 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void rejectsUnknownQuestions() {
        InquiryParser.ParsedInquiry parsed = parser.parse("푸딩은 어떻게 만드나요?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }
}
