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
    void extractsTitleHintWhenPeriodAndGroupNameAppearTogether() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 여행 모임에서 받을 돈이 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void extractsTitleHintWhenLastMonthAndSettlementTitleAppearTogether() {
        InquiryParser.ParsedInquiry parsed = parser.parse("지난달 회식 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.period()).isEqualTo(InquiryPeriod.LAST_MONTH);
        assertThat(parsed.titleHint()).isEqualTo("회식정산");
    }

    @Test
    void extractsTitleHintWhenStatusAndGroupNameAppearTogether() {
        InquiryParser.ParsedInquiry parsed = parser.parse("진행 중인 여행 모임 정산 상태는?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void prefersTheEarlierSendOrReceiveKeyword() {
        InquiryParser.ParsedInquiry receiveFirst = parser.parse("받을 사람한테 보낼 돈은 얼마야?");
        InquiryParser.ParsedInquiry sendFirst = parser.parse("보낼 정산에서 받을 사람 있어?");

        assertThat(receiveFirst.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(sendFirst.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
    }

    @Test
    void classifiesExpenseAndStatusWithoutPeriod() {
        assertThat(parser.parse("이번 달 지출을 요약해줘").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(parser.parse("정산 진행 상황은?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("정산 진행 상황은?").period()).isNull();
    }

    @Test
    void rejectsBlankAndUnknownQuestions() {
        assertThat(parser.parse(" ").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse(null).intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("푸딩은 어떻게 만드나요?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void classifiesChatQuickActionsAndMenuChips() {
        assertThat(parser.parse("보낼 정산").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("받을 정산").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("정산 상태").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("이번 주에 정산 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("이번 주에 정산 있어?").period()).isEqualTo(InquiryPeriod.THIS_WEEK);
        assertThat(parser.parse("이번 주에 마감해야 할 정산 알려줘").intent()).isEqualTo(InquiryIntent.DUE_SETTLEMENTS);
        assertThat(parser.parse("OCR 정산은 어떻게 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("나한테 아직 송금 안 한 사람 누구야?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("영수증 정산에서 받을 돈 있어?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }
}
