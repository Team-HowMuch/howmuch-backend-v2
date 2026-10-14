package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerGuideReply;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 비서 서비스 안내 LLM 어댑터")
class TreasurerGuideLlmAdapterTest {
    private static final TreasurerKnowledge KNOWLEDGE = TreasurerKnowledge.parse("""
            ## [PAY] 송금하기
            - 토스로 보낸다.
            """);

    @Mock
    private RestClient restClient;

    @Test
    @DisplayName("LLM을 쓰지 않는 설정이면 호출하지 않고 비어 있다")
    void disabledProvider_returnsEmpty() {
        TreasurerGuideLlmAdapter adapter = adapter("none", "");

        assertThat(adapter.generate("송금은 어떻게 해?", KNOWLEDGE)).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("빈 질문이나 문서 없이는 호출하지 않는다")
    void blankQuestionOrMissingKnowledge_returnsEmpty() {
        TreasurerGuideLlmAdapter adapter = adapter("openai", "test-key");

        assertThat(adapter.generate(" ", KNOWLEDGE)).isEmpty();
        assertThat(adapter.generate("송금은 어떻게 해?", null)).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    @DisplayName("호출이 실패하면 예외 대신 비어 있다")
    void clientFailure_returnsEmpty() {
        when(restClient.post()).thenThrow(new IllegalStateException("network"));
        TreasurerGuideLlmAdapter adapter = adapter("openai", "test-key");

        assertThat(adapter.generate("송금은 어떻게 해?", KNOWLEDGE)).isEmpty();
    }

    @Test
    @DisplayName("JSON 응답을 읽고 근거 ID의 대괄호와 코드 울타리를 걷어 낸다")
    void parse_guideJson() {
        TreasurerGuideLlmAdapter adapter = adapter("openai", "test-key");

        var reply = adapter.parse("""
                ```json
                {"route":"guide","answer":" 토스로 보내요. ","sources":["[pay]"," "],"followUps":["송금 취소돼?", 3]}
                ```""");

        assertThat(reply).hasValueSatisfying(value -> {
            assertThat(value.route()).isEqualTo(TreasurerGuideReply.Route.GUIDE);
            assertThat(value.answer()).isEqualTo("토스로 보내요.");
            assertThat(value.sources()).containsExactly("PAY");
            assertThat(value.followUps()).containsExactly("송금 취소돼?");
        });
    }

    @Test
    @DisplayName("데이터·관계없음 경로를 읽는다")
    void parse_otherRoutes() {
        TreasurerGuideLlmAdapter adapter = adapter("openai", "test-key");

        assertThat(adapter.parse("{\"route\":\"DATA\"}"))
                .hasValueSatisfying(value -> assertThat(value.route()).isEqualTo(TreasurerGuideReply.Route.DATA));
        assertThat(adapter.parse("{\"route\":\"UNRELATED\"}"))
                .hasValueSatisfying(value -> assertThat(value.route()).isEqualTo(TreasurerGuideReply.Route.UNRELATED));
    }

    @Test
    @DisplayName("경로를 알 수 없거나 객체가 아니면 비어 있다")
    void parse_invalid_returnsEmpty() {
        TreasurerGuideLlmAdapter adapter = adapter("openai", "test-key");

        assertThat(adapter.parse("{\"route\":\"SEND\",\"answer\":\"x\"}")).isEmpty();
        assertThat(adapter.parse("{\"answer\":\"x\"}")).isEmpty();
        assertThat(adapter.parse("[]")).isEmpty();
        assertThat(adapter.parse(" ")).isEmpty();
        assertThat(adapter.parse("송금은 토스로 하면 돼요.")).isEmpty();
    }

    @Test
    @DisplayName("시스템 프롬프트에 규칙과 문서 전체를 넣는다")
    void systemPrompt_containsRulesAndKnowledge() {
        String prompt = TreasurerGuideLlmAdapter.systemPrompt(KNOWLEDGE);

        assertThat(prompt).contains("[서비스 안내 문서]").contains("## [PAY] 송금하기").contains("\"route\"");
    }

    private TreasurerGuideLlmAdapter adapter(String provider, String apiKey) {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                provider,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi(apiKey, "gpt-4o-mini", "https://api.openai.com/v1"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        return new TreasurerGuideLlmAdapter(restClient, properties, new ObjectMapper());
    }
}
