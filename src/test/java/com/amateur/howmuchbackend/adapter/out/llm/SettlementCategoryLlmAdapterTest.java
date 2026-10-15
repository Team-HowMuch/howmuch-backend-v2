package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Candidate;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Outcome;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Request;
import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("정산 카테고리 AI 분류 어댑터")
class SettlementCategoryLlmAdapterTest {
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final Request REQUEST = new Request(
            List.of("행복분식 강남점"), List.of("떡볶이", "순대"),
            List.of(new Candidate("FOOD", "식비", "식당, 배달"), new Candidate("CAFE_SNACK", "카페·간식", null)));

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("LLM을 쓰지 않는 설정이면 호출하지 않고 모름으로 답한다")
    void disabled_returnsUnknownWithoutCall() {
        SettlementCategoryLlmAdapter adapter = adapter("none", "");

        assertThat(adapter.available()).isFalse();
        assertThat(adapter.classify(REQUEST).outcome()).isEqualTo(Outcome.UNKNOWN);
        server.verify();
    }

    @Test
    @DisplayName("OpenAI 답의 카테고리 코드를 읽는다")
    void openAi_classified() {
        server.expect(requestTo(OPENAI_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess(openAiResponse("{\\\"category\\\":\\\"food\\\"}"), MediaType.APPLICATION_JSON));

        var result = adapter("openai", "test-key").classify(REQUEST);

        assertThat(result.outcome()).isEqualTo(Outcome.CLASSIFIED);
        assertThat(result.categoryCode()).isEqualTo("FOOD");
        server.verify();
    }

    @Test
    @DisplayName("OpenAI가 거절하면 공급자 오류로 답한다")
    void openAi_rejected_providerError() {
        server.expect(requestTo(OPENAI_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":{\"code\":\"rate_limit\"}}"));

        assertThat(adapter("openai", "test-key").classify(REQUEST).outcome()).isEqualTo(Outcome.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("응답 본문을 읽을 수 없거나 내용이 비었으면 잘못된 응답이다")
    void openAi_brokenBody() {
        server.expect(requestTo(OPENAI_URL)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertThat(adapter("openai", "test-key").classify(REQUEST).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);

        server.reset();
        server.expect(requestTo(OPENAI_URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(adapter("openai", "test-key").classify(REQUEST).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("답 형식을 읽는다: 코드, 모름, 형식 오류")
    void parse_outcomes() {
        SettlementCategoryLlmAdapter adapter = adapter("openai", "test-key");

        assertThat(adapter.parse("```json\n{\"category\":\" Cafe_Snack \"}\n```").categoryCode())
                .isEqualTo("CAFE_SNACK");
        assertThat(adapter.parse("{\"category\":\"unknown\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(adapter.parse("{\"category\":\"\"}").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(adapter.parse("{\"category\":3}").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(adapter.parse("{\"code\":\"FOOD\"}").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(adapter.parse("[]").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(adapter.parse("식비입니다").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(adapter.parse(" ").outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("프롬프트에 후보 카테고리와 가게 이름·품목명만 넣는다")
    void prompts_containCandidatesAndMaterial() {
        String system = SettlementCategoryLlmAdapter.systemPrompt(REQUEST.candidates());
        String user = SettlementCategoryLlmAdapter.userPrompt(REQUEST);

        assertThat(system).contains("- FOOD: 식비 (식당, 배달)").contains("- CAFE_SNACK: 카페·간식")
                .contains("UNKNOWN").doesNotContain("UNCATEGORIZED");
        assertThat(user).contains("- 행복분식 강남점").contains("- 떡볶이").contains("- 순대");
        assertThat(SettlementCategoryLlmAdapter.userPrompt(new Request(List.of("가게"), List.of(), List.of())))
                .contains("품목명:\n- 없음");
    }

    @Test
    @DisplayName("OpenAI 요청 본문은 JSON 형식을 지정하고 temperature를 넣지 않는다")
    void openAiBody_jsonWithoutTemperature() {
        var body = adapter("openai", "test-key").openAiBody("규칙", "입력");

        assertThat(body).containsOnlyKeys("model", "reasoning_effort", "messages", "response_format");
        assertThat(body).containsEntry("response_format", Map.of("type", "json_object"));
    }

    private SettlementCategoryLlmAdapter adapter(String provider, String apiKey) {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                provider,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi(apiKey, "gpt-6-luna", "https://api.openai.com/v1", "low"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        return new SettlementCategoryLlmAdapter(builder.build(), properties, new ObjectMapper());
    }

    private static String openAiResponse(String escapedContent) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escapedContent + "\"}}]}";
    }
}
