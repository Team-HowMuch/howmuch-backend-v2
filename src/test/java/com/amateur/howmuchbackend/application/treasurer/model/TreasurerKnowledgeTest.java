package com.amateur.howmuchbackend.application.treasurer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI 비서 서비스 안내 문서")
class TreasurerKnowledgeTest {
    @Test
    @DisplayName("섹션 제목과 항목 앞의 ID를 근거 ID로 모은다")
    void parse_collectsHeadingAndBulletIds() {
        TreasurerKnowledge knowledge = TreasurerKnowledge.parse("""
                # 얼마 서비스 안내
                ## [PAY] 송금하기
                - 토스로 보낸다. [토스로 N원 보내기] 버튼이 있다.
                - [AI-REPORT] 답변을 신고할 수 있다.
                ## [NOT-YET] 아직 제공하지 않는 기능
                """);

        assertThat(knowledge.sourceIds()).containsExactlyInAnyOrder("PAY", "AI-REPORT", "NOT-YET");
        assertThat(knowledge.knows("PAY")).isTrue();
        assertThat(knowledge.knows("토스로 N원 보내기")).isFalse();
        assertThat(knowledge.knows(null)).isFalse();
    }

    @Test
    @DisplayName("같은 근거 ID가 두 번 나오면 어느 쪽 근거인지 알 수 없어 거부한다")
    void parse_duplicateId_rejected() {
        assertThatThrownBy(() -> TreasurerKnowledge.parse("""
                ## [PAY] 송금하기
                ## [PAY] 송금 다시
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAY");
    }

    @Test
    @DisplayName("비어 있거나 근거 ID가 없는 문서는 거부한다")
    void parse_emptyOrWithoutIds_rejected() {
        assertThatThrownBy(() -> TreasurerKnowledge.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreasurerKnowledge.parse("# 제목만 있다"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
