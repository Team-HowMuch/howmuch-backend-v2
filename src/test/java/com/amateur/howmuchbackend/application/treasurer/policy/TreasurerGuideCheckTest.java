package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerGuideReply;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 비서 안내 답변 검사")
class TreasurerGuideCheckTest {
    private static final TreasurerKnowledge KNOWLEDGE = TreasurerKnowledge.parse("""
            ## [REMIND] 독촉 알림
            - 한 번 보내면 30분 동안 다시 보낼 수 없다.
            ## [RECAP] 월간 리캡
            - 4명이 40,000원을 나눴으면 10,000원으로 센다.
            ## [NOT-YET] 아직 제공하지 않는 기능
            - 정기 정산
            """);

    private final TreasurerGuideCheck guideCheck = new TreasurerGuideCheck();

    @Test
    @DisplayName("근거가 문서에 있는 안내 답변을 받아들인다")
    void validGuide_accepted() {
        var accepted = check(guide("독촉 알림은 30분 뒤에 다시 보낼 수 있어요.", List.of("REMIND"), List.of()), KNOWLEDGE);

        assertThat(accepted).hasValueSatisfying(value -> {
            assertThat(value.answer()).isEqualTo("독촉 알림은 30분 뒤에 다시 보낼 수 있어요.");
            assertThat(value.sources()).containsExactly("REMIND");
        });
    }

    @Test
    @DisplayName("미제공 기능 섹션만 근거로 든 답도 받아들인다")
    void notYetOnly_accepted() {
        assertThat(check(guide("정기 정산은 아직 제공하지 않는 기능이에요.", List.of("NOT-YET"), List.of()), KNOWLEDGE))
                .isPresent();
    }

    @Test
    @DisplayName("안내가 아닌 경로의 응답은 답변으로 쓰지 않는다")
    void nonGuideRoute_rejected() {
        assertThat(check(new TreasurerGuideReply(
                TreasurerGuideReply.Route.DATA, "보낼 돈을 조회할게요.", List.of("REMIND"), List.of()), KNOWLEDGE))
                .isEmpty();
        assertThat(check(null, KNOWLEDGE)).isEmpty();
    }

    @Test
    @DisplayName("근거가 없거나 문서에 없는 근거를 댄 답은 지어낸 것으로 본다")
    void missingOrUnknownSource_rejected() {
        assertThat(check(guide("독촉은 하루 세 번까지예요.", List.of(), List.of()), KNOWLEDGE)).isEmpty();
        assertThat(check(guide("독촉은 하루 세 번까지예요.", List.of("REMIND", "MADE-UP"), List.of()), KNOWLEDGE))
                .isEmpty();
    }

    @Test
    @DisplayName("문서에 없는 금액을 말하면 개인 금액을 지어낸 것으로 본다")
    void unknownAmount_rejected() {
        assertThat(check(guide("보낼 돈은 12,000원이에요.", List.of("REMIND"), List.of()), KNOWLEDGE)).isEmpty();
    }

    @Test
    @DisplayName("문서에 그대로 있는 금액 예시는 허용한다")
    void documentedAmount_accepted() {
        assertThat(check(guide("4명이 40,000원을 나누면 10,000원으로 세요.", List.of("RECAP"), List.of()), KNOWLEDGE))
                .isPresent();
    }

    @Test
    @DisplayName("빈 답이나 너무 긴 답은 쓰지 않는다")
    void blankOrTooLong_rejected() {
        assertThat(check(guide(" ", List.of("REMIND"), List.of()), KNOWLEDGE)).isEmpty();
        assertThat(check(guide("가".repeat(TreasurerGuideCheck.MAX_ANSWER_LENGTH + 1), List.of("REMIND"), List.of()),
                KNOWLEDGE)).isEmpty();
        assertThat(check(guide("가".repeat(TreasurerGuideCheck.MAX_ANSWER_LENGTH), List.of("REMIND"), List.of()),
                KNOWLEDGE)).isPresent();
    }

    @Test
    @DisplayName("마크다운 굵게와 제목 기호는 지운다")
    void markdown_stripped() {
        var accepted = check(guide("## 독촉\n**30분** 뒤에 다시 보낼 수 있어요.", List.of("REMIND"), List.of()), KNOWLEDGE);

        assertThat(accepted).hasValueSatisfying(value ->
                assertThat(value.answer()).isEqualTo("독촉\n30분 뒤에 다시 보낼 수 있어요."));
    }

    @Test
    @DisplayName("후속 질문은 짧은 문장만 중복 없이 세 개까지 남긴다")
    void followUps_filtered() {
        var accepted = check(guide("독촉 알림은 30분 뒤에 다시 보낼 수 있어요.", List.of("REMIND"), List.of(
                " 송금은 어떻게 해? ",
                "송금은 어떻게 해?",
                "",
                "가".repeat(TreasurerGuideCheck.MAX_FOLLOW_UP_LENGTH + 1),
                "http://example.com 열어줘",
                "그룹은 어떻게 나가?",
                "리캡은 언제 와?",
                "네 번째 질문"
        )), KNOWLEDGE);

        assertThat(accepted).hasValueSatisfying(value -> assertThat(value.followUps())
                .containsExactly("송금은 어떻게 해?", "그룹은 어떻게 나가?", "리캡은 언제 와?"));
    }

    @Test
    @DisplayName("문서에 없는 시간·횟수 같은 숫자도 틀린 안내로 본다")
    void undocumentedNonMoneyNumber_rejected() {
        var outcome = guideCheck.check(guide("독촉 알림은 하루 5번까지 보낼 수 있어요.", List.of("REMIND"), List.of()), KNOWLEDGE);

        assertThat(outcome.accepted()).isEmpty();
        assertThat(outcome.rejectedBecause()).isEqualTo("undocumented number");
        assertThat(check(guide("독촉 알림은 5분 뒤에 다시 보낼 수 있어요.", List.of("REMIND"), List.of()), KNOWLEDGE)).isEmpty();
    }

    @Test
    @DisplayName("문서 숫자의 일부만 떼어 말하거나 다른 표기로 쓴 금액도 막는다")
    void partialOrOtherNotationAmount_rejected() {
        assertThat(check(guide("수수료는 0원이에요.", List.of("RECAP"), List.of()), KNOWLEDGE)).isEmpty();
        assertThat(check(guide("수수료는 1만원이에요.", List.of("RECAP"), List.of()), KNOWLEDGE)).isEmpty();
        assertThat(check(guide("수수료는 ₩3,000이에요.", List.of("RECAP"), List.of()), KNOWLEDGE)).isEmpty();
    }

    @Test
    @DisplayName("거절 이유를 남긴다")
    void rejection_hasReason() {
        assertThat(guideCheck.check(guide("a", List.of(), List.of()), KNOWLEDGE).rejectedBecause()).isEqualTo("no source");
        assertThat(guideCheck.check(guide("a", List.of("MADE-UP"), List.of()), KNOWLEDGE).rejectedBecause())
                .isEqualTo("unknown source");
        assertThat(guideCheck.check(guide("독촉 알림은 30분 뒤에 다시 보낼 수 있어요.", List.of("REMIND"), List.of()), KNOWLEDGE)
                .rejectedBecause()).isNull();
    }

    @Test
    @DisplayName("코드 울타리의 언어 표시도 지운다")
    void anyFenceLanguage_stripped() {
        assertThat(check(guide("```kotlin\n30분 뒤에 다시 보낼 수 있어요.\n```", List.of("REMIND"), List.of()), KNOWLEDGE))
                .hasValueSatisfying(value -> assertThat(value.answer()).isEqualTo("30분 뒤에 다시 보낼 수 있어요."));
    }

    @Test
    @DisplayName("문서에 없는 숫자가 들어간 후속 질문은 버린다")
    void followUpWithUndocumentedNumber_dropped() {
        assertThat(check(guide("독촉 알림은 30분 뒤에 다시 보낼 수 있어요.", List.of("REMIND"),
                List.of("보낼 돈 12,000원 맞아?", "30분 지나면 다시 돼?")), KNOWLEDGE))
                .hasValueSatisfying(value -> assertThat(value.followUps()).containsExactly("30분 지나면 다시 돼?"));
    }

    private Optional<TreasurerGuideCheck.Accepted> check(TreasurerGuideReply reply, TreasurerKnowledge knowledge) {
        return guideCheck.check(reply, knowledge).accepted();
    }

    private TreasurerGuideReply guide(String answer, List<String> sources, List<String> followUps) {
        return new TreasurerGuideReply(TreasurerGuideReply.Route.GUIDE, answer, sources, followUps);
    }
}
