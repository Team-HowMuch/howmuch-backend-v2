package com.amateur.howmuchbackend.application.treasurer.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("사용법 질문 말투 판별")
class InquiryHowToPolicyTest {
    private final InquiryHowToPolicy policy = new InquiryHowToPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "송금은 어떻게 해?",
            "결제수단 등록 방법 알려줘",
            "받을 계좌 바꾸려면?",
            "정산 링크는 어디서 보내?",
            "정산 삭제할 수 있어?",
            "그룹은 어떻게 나가?",
            "알림이 안 와",
            "영수증 인식은 어디서 해?"
    })
    void howToPhrasing_detected(String question) {
        assertThat(policy.asksHowTo(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "이번 달 보낼 정산 얼마야?",
            "받을 돈 있어?",
            "정산 상태",
            "여행 모임에서 내가 뭘 내야 해?",
            "이번 달 보낼 돈 어떻게 돼?",
            "받을 돈 어떻게 돼?",
            "정산 진행 상황 어떻게 돼?",
            "지난달 지출 어떻게 돼?",
            "보낼 돈 뭐야?",
            "마감 임박한 정산 뭐야?",
            "왜 이렇게 보낼 돈이 많아?",
            "결제 내역 보여줄 수 있어?"
    })
    void dataQuestion_notDetected(String question) {
        assertThat(policy.asksHowTo(question)).isFalse();
    }

    @Test
    void blank_notDetected() {
        assertThat(policy.asksHowTo(null)).isFalse();
        assertThat(policy.asksHowTo(" ")).isFalse();
    }
}
