package com.amateur.howmuchbackend.adapter.out.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerLlmResponseParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void openAiResponse_messageContent_parsed() throws Exception {
        String content = TreasurerLlmResponseParser.openAiContent(objectMapper.readTree("""
                {"choices":[{"message":{"content":"보낼 정산이 1건 있어요."}}]}
                """));

        assertThat(content).isEqualTo("보낼 정산이 1건 있어요.");
    }

    @Test
    void geminiResponse_textParts_parsed() throws Exception {
        String content = TreasurerLlmResponseParser.geminiContent(objectMapper.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"이번 달 지출은 12,000원입니다."}]}}]}
                """));

        assertThat(content).isEqualTo("이번 달 지출은 12,000원입니다.");
    }

    @Test
    void geminiResponse_missingParts_returnsNull() throws Exception {
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
}
