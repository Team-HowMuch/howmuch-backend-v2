package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.InquiryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryScopePolicyTest {
    private final InquiryScopePolicy policy = new InquiryScopePolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "정산은 어떻게 하는 거야?",
            "정산이 뭐야?",
            "보낼 정산이랑 받을 정산은 뭐가 달라?",
            "정산 기한이 지나면 어떻게 돼?",
            "이 서비스는 어떻게 사용하는 거야?",
            "내가 아직 돈 안 보낸 게 있어?",
            "누구한테 돈 줘야 해?",
            "나한테 돈 들어올 거 있어?",
            "여행에서 내가 부담해야 하는 금액이 있어?",
            "여행 모임에서 내가 뭘 내야 해?",
            "영수증으로 정산 만들 수 있어?"
    })
    void inScopeSettlementOrServiceQuestion_related(String question) {
        assertThat(policy.classify(question)).isEqualTo(InquiryScope.RELATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "푸딩 만드는 방법 알려줘.",
            "오늘 날씨 알려줘.",
            "Java 공부법 알려줘.",
            "영화 추천해줘.",
            "주식 추천해줘.",
            "공원이 어디야?"
    })
    void treatsQuestionsWithoutServiceVocabularyAsUnrelated(String question) {
        assertThat(policy.classify(question)).isEqualTo(InquiryScope.UNRELATED);
    }

    @Test
    void treatsBlankQuestionAsUnrelated() {
        assertThat(policy.classify(null)).isEqualTo(InquiryScope.UNRELATED);
        assertThat(policy.classify(" ")).isEqualTo(InquiryScope.UNRELATED);
    }
}
