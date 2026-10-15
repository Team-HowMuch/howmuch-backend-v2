package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 비서 OpenAI 요청 본문")
class OpenAiChatRequestTest {

    @Test
    @DisplayName("기본 설정이면 gpt-6-luna에 추론 강도 low를 보내고 temperature는 보내지 않는다")
    void defaults_lunaWithLowEffort_withoutTemperature() {
        Map<String, Object> body = OpenAiChatRequest.body(
                new TreasurerLlmProperties.OpenAi("test-key", null, null, null), "규칙", "질문");

        assertThat(body).containsOnlyKeys("model", "reasoning_effort", "messages");
        assertThat(body).containsEntry("model", "gpt-6-luna").containsEntry("reasoning_effort", "low");
        assertThat(body).doesNotContainKey("temperature");
    }

    @Test
    @DisplayName("시스템 지시 다음에 사용자 질문을 보낸다")
    void messages_systemThenUser() {
        Map<String, Object> body = OpenAiChatRequest.body(
                new TreasurerLlmProperties.OpenAi("test-key", null, null, null), "규칙", "질문");

        assertThat(body.get("messages")).isEqualTo(List.of(
                Map.of("role", "system", "content", "규칙"),
                Map.of("role", "user", "content", "질문")
        ));
    }

    @Test
    @DisplayName("추론 강도를 빈 값으로 두면 보내지 않는다")
    void blankEffort_omitted() {
        Map<String, Object> body = OpenAiChatRequest.body(
                new TreasurerLlmProperties.OpenAi("test-key", "other-model", null, " "), "규칙", "질문");

        assertThat(body).containsOnlyKeys("model", "messages");
        assertThat(body).containsEntry("model", "other-model");
    }

    @Test
    @DisplayName("설정한 추론 강도는 앞뒤 공백을 걷어 그대로 보낸다")
    void configuredEffort_trimmed() {
        Map<String, Object> body = OpenAiChatRequest.body(
                new TreasurerLlmProperties.OpenAi("test-key", null, null, " medium "), "규칙", "질문");

        assertThat(body).containsEntry("reasoning_effort", "medium");
    }
}
