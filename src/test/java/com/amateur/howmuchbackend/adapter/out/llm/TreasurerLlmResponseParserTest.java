package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort.TreasurerAnswerDraft;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerLlmResponseParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsOpenAiMessageContent() throws Exception {
        String content = TreasurerLlmResponseParser.openAiContent(objectMapper.readTree("""
                {"choices":[{"message":{"content":"보낼 정산이 1건 있어요."}}]}
                """));

        assertThat(content).isEqualTo("보낼 정산이 1건 있어요.");
    }

    @Test
    void readsGeminiTextParts() throws Exception {
        String content = TreasurerLlmResponseParser.geminiContent(objectMapper.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"이번 달 지출은 12,000원입니다."}]}}]}
                """));

        assertThat(content).isEqualTo("이번 달 지출은 12,000원입니다.");
    }

    @Test
    void returnsNullWhenGeminiPartsAreMissing() throws Exception {
        assertThat(TreasurerLlmResponseParser.geminiContent(objectMapper.readTree("""
                {"candidates":[{"content":{"parts":[]}}]}
                """))).isNull();
        assertThat(TreasurerLlmResponseParser.openAiContent(objectMapper.readTree("{}"))).isNull();
    }

    @Test
    void sanitizesMarkdownAndTruncates() {
        assertThat(TreasurerLlmResponseParser.sanitize("```답변```")).isEqualTo("답변");
        assertThat(TreasurerLlmResponseParser.sanitize("**보낼 정산이 있어요.**")).isEqualTo("보낼 정산이 있어요.");
        assertThat(TreasurerLlmResponseParser.sanitize("# 안내\n보낼 정산이 있어요.")).isEqualTo("안내\n보낼 정산이 있어요.");
        assertThat(TreasurerLlmResponseParser.sanitize("   ")).isNull();
    }

    @Test
    void rejectsAnswersThatInventAmounts() {
        var draft = new TreasurerAnswerDraft(
                "이번 달 보낼 정산은 얼마야?",
                "SEND_SETTLEMENTS",
                "현재 월 기준으로 조회했어요.",
                "현재 월 기준으로 조회했어요. 보낼 정산은 1건, 총 12,000원이에요.",
                12_000L,
                1,
                List.of()
        );

        assertThat(TreasurerLlmResponseParser.usesOnlyKnownNumbers("보낼 정산이 1건, 12,000원이에요.", draft)).isTrue();
        assertThat(TreasurerLlmResponseParser.usesOnlyKnownNumbers("보낼 정산이 3건, 99,000원이에요.", draft)).isFalse();
        assertThat(TreasurerLlmResponseParser.usesOnlyKnownNumbers("8월 기준으로 보낼 정산이 1건, 12,000원이에요.", draft)).isTrue();

        var statusDraft = new TreasurerAnswerDraft(
                "정산 진행 상태는?",
                "SETTLEMENT_STATUS",
                "이번 달 기준으로 조회했어요.",
                "이번 달 기준으로 조회했어요. 진행 중인 정산 1건, 완료된 정산 0건이에요.",
                12_000L,
                1,
                List.of()
        );
        assertThat(TreasurerLlmResponseParser.usesOnlyKnownNumbers("진행 중인 정산 1건, 완료된 정산 0건이에요.", statusDraft)).isTrue();
    }
}
