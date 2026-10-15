package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void titleHint_statusAndGroupName_extracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("진행 중인 여행 모임 정산 상태는?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parsed.titleHint()).isEqualTo("여행모임");
    }

    @Test
    void priorityQuestion_withoutOrBothDirections_classifiedPriority() {
        for (String question : List.of(
                "뭐부터 처리해?",
                "먼저 처리할 정산 추천해줘",
                "무엇부터 해야 할까?",
                "뭘 먼저 하면 돼?",
                "어떤 정산부터 처리해야 해?",
                "정산 우선순위 알려줘",
                "급한 정산 알려줘",
                "밀린 정산 정리해줘",
                "돈부터 보내야 하는 거 뭐야? 받을 것도 있어",
                "보낼 정산과 받을 정산 정리해줘",
                "받을 돈이랑 보낼 돈 중에 뭐부터 해?",
                "이번 달 정산 뭐부터 하면 돼?"
        )) {
            assertThat(parser.parse(question).intent()).as(question).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        }
        assertThat(parser.parse("이번 달 정산 뭐부터 하면 돼?").period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(parser.parse("뭐부터 처리해?").period()).isNull();
    }

    @Test
    void priorityQuestion_oneDirection_keepsDirectionIntent() {
        assertThat(parser.parse("보낼 정산 뭐부터 보내?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("받을 정산 정리해줘").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("나한테 아직 송금 안 한 사람 먼저 알려줘").intent())
                .isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void priorityWeakWord_withoutSettlementContext_notPriority() {
        assertThat(parser.parse("영화 추천해줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("방 정리 좀 도와줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("나 지금 급해").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("처리해야 할 정산 알려줘").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("보낼 정산이랑 받을 정산은 각각 얼마야?").intent())
                .isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("자동 리마인드는 몇 시부터 보내?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("회원가입 먼저 해야 해?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("계좌 먼저 등록해야 해?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("받을 사람한테 보낼 돈 먼저 알려줘").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void priorityWeakWord_withExplicitIntent_keepsExplicitIntent() {
        assertThat(parser.parse("이번 달 모임 지출 정리해줘").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(parser.parse("정산 진행 상황 정리해줘").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("급한 정산 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("이번 주 마감할 정산 정리해줘").intent()).isEqualTo(InquiryIntent.DUE_SETTLEMENTS);
        assertThat(parser.parse("영수증 정산 어떻게 정리해?").intent()).isEqualTo(InquiryIntent.GUIDE);
    }

    @Test
    void priorityWords_notGrammarForOtherIntents() {
        InquiryParser.ParsedInquiry parsed = parser.parse("정리모임에서 보낼 돈 알려줘");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("정리모임");
    }

    @Test
    void priorityQuestion_titleHintExtracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("제주 여행 정산 중에 뭐부터 해?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("제주여행정산");
    }

    @Test
    void sendIntent_competingKeywords_earlierPreferred() {
        InquiryParser.ParsedInquiry receiveFirst = parser.parse("받을 사람한테 보낼 돈은 얼마야?");
        InquiryParser.ParsedInquiry sendFirst = parser.parse("보낼 정산에서 받을 사람 있어?");

        assertThat(receiveFirst.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(sendFirst.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
    }

    @Test
    void expenseQuery_withoutPeriod_classifiedWithStatus() {
        assertThat(parser.parse("이번 달 지출을 요약해줘").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(parser.parse("정산 진행 상황은?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("정산 진행 상황은?").period()).isNull();
    }

    @Test
    void blankOrUnknownQuestion_classifiedUnknown() {
        assertThat(parser.parse(" ").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse(null).intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("푸딩은 어떻게 만드나요?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void quickActionsAndMenuChips_classified() {
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
    void colloquialSendPhrase_classified() {
        assertThat(parser.parse("내가 내야 할 돈 얼마야?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("아직 안 낸 정산 있어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("나 아직 돈 안 냈어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("친구한테 줘야 하는 돈 있어?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parser.parse("내가 부담해야 할 금액은?").intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
    }

    @Test
    void colloquialReceivePhrase_classified() {
        assertThat(parser.parse("돈 언제 들어와?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("들어올 돈 있어?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void colloquialExpensePhrase_classified() {
        assertThat(parser.parse("이번 달에 얼마 썼어?").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(parser.parse("이번 달에 얼마 썼어?").period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(parser.parse("이번 달에 얼마나 지불했어?").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
    }

    @Test
    void colloquialStatusPhrase_classified() {
        assertThat(parser.parse("아직 안 끝난 정산 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("남은 정산 뭐 있어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parser.parse("처리해야 할 정산 알려줘").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
    }

    @Test
    void unrelatedQuestion_classifiedUnknown() {
        assertThat(parser.parse("오늘 날씨 어때?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("부담 없이 써도 돼?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("김치찌개 레시피 알려줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("영화 추천해줘").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void titleHint_grammarRemainder_notExtracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("보낼 정산이랑 받을 정산은 각각 얼마야?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void titleHint_contiguousQuestionText_extracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("제주도 여행 정산 어디까지 됐어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(parsed.titleHint()).isEqualTo("제주도여행정산");
    }

    @Test
    void titleHint_originalLetterCase_preserved() {
        InquiryParser.ParsedInquiry parsed = parser.parse("MT 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("MT정산");
    }

    @Test
    void titleHint_nonFilteringIntent_ignored() {
        InquiryParser.ParsedInquiry parsed = parser.parse("OCR 정산은 어떻게 해?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parsed.titleHint()).isNull();
    }

    @Test
    void titleHint_particleRemainder_notExtracted() {
        assertThat(parser.parse("이번 주에는 정산 있어?").titleHint()).isNull();
        assertThat(parser.parse("저번 주에 정산 있어?").titleHint()).isNull();
        assertThat(parser.parse("이번 달 중에 정산 얼마 남았어?").titleHint()).isNull();
        assertThat(parser.parse("새 멤버 들어올 건데 정산 어떻게 나눠?").titleHint()).isNull();
    }

    @Test
    void titleHint_pronoun_notExtracted() {
        assertThat(parser.parse("내 정산 중에 뭐가 남았어?").titleHint()).isNull();
        assertThat(parser.parse("우리 모임에서 받을 돈 있어?").titleHint()).isNull();
        assertThat(parser.parse("이 정산 상태 알려줘").titleHint()).isNull();
    }

    @Test
    void titleHint_singleCharacter_extracted() {
        InquiryParser.ParsedInquiry parsed = parser.parse("봄 정산에서 받을 돈 있어?");

        assertThat(parsed.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parsed.titleHint()).isEqualTo("봄정산");
    }

    @Test
    void titleHint_prefixLength_limited() {
        assertThat("가나다라마바사아자차카타파하거너더러머버".length()).isEqualTo(MAX_TITLE_PREFIX);
        String twenty = "가나다라마바사아자차카타파하거너더러머버";
        String longer = twenty + "서";

        assertThat(parser.parse(twenty + " 정산에서 받을 돈 있어?").titleHint()).isEqualTo(twenty + "정산");
        assertThat(parser.parse(longer + " 정산에서 받을 돈 있어?").titleHint()).isNull();
    }

    @Test
    void unpaidByOthers_moneyComingToUser_classified() {
        assertThat(parser.parse("회비 안 낸 사람 알려줘").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("나한테 안 보낸 사람 누구야?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("아직 안 낸 사람 누구야?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
    }

    @Test
    void everydayWord_requiresSettlementContext() {
        assertThat(parser.parse("밥 남았어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("치킨 남은 거 있어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("그 사람 언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("숙제 줘야 해?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("숙제 안 냈어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
    }

    @Test
    void contextWord_wholeWordMatch() {
        assertThat(parser.parse("돈까스 남았어?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("돈까스 언제 들어와?").intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(parser.parse("돈 언제 들어와?").intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(parser.parse("금액이 얼마나 남았어?").intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
    }

    @Test
    void punctuation_titleBoundary_accepted() {
        assertThat(parser.parse("\"여행 모임\"에서 받을 돈 있어?").titleHint()).isEqualTo("여행모임");
        assertThat(parser.parse("(여행 모임)에서 받을 돈 있어?").titleHint()).isEqualTo("여행모임");
    }

    @Test
    void guideIntent_competingEverydayVerbs_preserved() {
        assertThat(parser.parse("영수증 OCR은 어떻게 처리해야 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("영수증 남은 거 어떻게 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("영수증 정산은 어떻게 처리해야 해?").intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(parser.parse("이번 달 지출 영수증 보여줘").intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
    }
}
