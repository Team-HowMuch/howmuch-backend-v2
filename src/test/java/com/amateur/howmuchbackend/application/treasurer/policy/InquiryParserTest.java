package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryParserTest {
    private static final int MAX_TITLE_PREFIX = 20;

    private final InquiryParser parser = new InquiryParser();

    @Test
    void settlementQuery_sendIntentThisMonth_classified() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 내가 보낼 정산은 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
    }

    @Test
    void titleHint_groupName_extracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("여행 모임에서 받을 돈이 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void titleHint_settlementTitle_extractedWithoutQuestionRemainder() {
        InquiryParser.ParsedInquiry parsed = parser.parse("회식 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("회식정산");
    }

    @Test
    void titleHint_sendGrammar_notExtractedAsTitle() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 내가 보낼 정산은 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void titleHint_periodAndGroupName_extracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("이번 달 여행 모임에서 받을 돈이 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void titleHint_lastMonthAndTitle_extracted() {
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

    @Test
    void classifiesColloquialSendExpressions() {
        assertThat(parser.parse("내가 내야 할 돈 얼마야?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("아직 안 낸 정산 있어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("나 아직 돈 안 냈어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("친구한테 줘야 하는 돈 있어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("내가 부담해야 할 금액은?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
    }

    @Test
    void classifiesColloquialReceiveExpressions() {
        assertThat(parser.parse("돈 언제 들어와?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("들어올 돈 있어?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void classifiesColloquialExpenseExpressions() {
        assertThat(parser.parse("이번 달에 얼마 썼어?").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(parser.parse("이번 달에 얼마 썼어?").period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(parser.parse("이번 달에 얼마나 지불했어?").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
    }

    @Test
    void classifiesColloquialStatusExpressions() {
        assertThat(parser.parse("아직 안 끝난 정산 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("남은 정산 뭐 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("처리해야 할 정산 알려줘").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
    }

    @Test
    void keepsUnrelatedQuestionsUnknown() {
        assertThat(parser.parse("오늘 날씨 어때?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("부담 없이 써도 돼?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("김치찌개 레시피 알려줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("영화 추천해줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void doesNotBuildTitleFromGrammarLeftovers() {
        InquiryParser.ParsedInquiry parsed = parser.parse("보낼 정산이랑 받을 정산은 각각 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void extractsTitleHintAsContiguousTextOfTheQuestion() {
        InquiryParser.ParsedInquiry parsed = parser.parse("제주도 여행 정산 어디까지 됐어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parsed.titleHint()).isEqualTo("제주도여행정산");
    }

    @Test
    void keepsOriginalLetterCaseInTitleHint() {
        InquiryParser.ParsedInquiry parsed = parser.parse("MT 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("MT정산");
    }

    @Test
    void ignoresTitleHintWhenIntentDoesNotFilterByTitle() {
        InquiryParser.ParsedInquiry parsed = parser.parse("OCR 정산은 어떻게 해?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void doesNotTreatParticleLeftoverAsTitle() {
        assertThat(parser.parse("이번 주에는 정산 있어?").titleHint()).isNull();
        assertThat(parser.parse("저번 주에 정산 있어?").titleHint()).isNull();
        assertThat(parser.parse("이번 달 중에 정산 얼마 남았어?").titleHint()).isNull();
        assertThat(parser.parse("새 멤버 들어올 건데 정산 어떻게 나눠?").titleHint()).isNull();
    }

    @Test
    void doesNotTreatPronounAsTitle() {
        assertThat(parser.parse("내 정산 중에 뭐가 남았어?").titleHint()).isNull();
        assertThat(parser.parse("우리 모임에서 받을 돈 있어?").titleHint()).isNull();
        assertThat(parser.parse("이 정산 상태 알려줘").titleHint()).isNull();
    }

    @Test
    void extractsSingleCharacterTitle() {
        InquiryParser.ParsedInquiry parsed = parser.parse("봄 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("봄정산");
    }

    @Test
    void limitsTitlePrefixLength() {
        assertThat("가나다라마바사아자차카타파하거너더러머버".length()).isEqualTo(MAX_TITLE_PREFIX);
        String twenty = "가나다라마바사아자차카타파하거너더러머버";
        String longer = twenty + "서";

        assertThat(parser.parse(twenty + " 정산에서 받을 돈 있어?").titleHint()).isEqualTo(twenty + "정산");
        assertThat(parser.parse(longer + " 정산에서 받을 돈 있어?").titleHint()).isNull();
    }

    @Test
    void readsUnpaidByOthersAsMoneyComingToMe() {
        assertThat(parser.parse("회비 안 낸 사람 알려줘").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("나한테 안 보낸 사람 누구야?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("아직 안 낸 사람 누구야?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void requiresSettlementContextForEverydayWords() {
        assertThat(parser.parse("밥 남았어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("치킨 남은 거 있어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("그 사람 언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("숙제 줘야 해?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("숙제 안 냈어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void readsContextWordsAsWholeWords() {
        assertThat(parser.parse("돈까스 남았어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("돈까스 언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("돈 언제 들어와?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("금액이 얼마나 남았어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
    }

    @Test
    void acceptsPunctuationAsTitleBoundary() {
        assertThat(parser.parse("\"여행 모임\"에서 받을 돈 있어?").titleHint()).isEqualTo("여행모임");
        assertThat(parser.parse("(여행 모임)에서 받을 돈 있어?").titleHint()).isEqualTo("여행모임");
    }

    @Test
    void keepsGuideIntentWhenOnlyEverydayVerbsCompete() {
        assertThat(parser.parse("영수증 OCR은 어떻게 처리해야 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("영수증 남은 거 어떻게 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("영수증 정산은 어떻게 처리해야 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("이번 달 지출 영수증 보여줘").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
    }
}
