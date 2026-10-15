package com.amateur.howmuchbackend.infra.treasurer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 비서 LLM 설정 바인딩")
class TreasurerLlmPropertiesTest {

    @Test
    @DisplayName("OpenAI 설정이 없으면 gpt-6-luna와 추론 강도 low를 쓴다")
    void missingOpenAi_defaults() {
        TreasurerLlmProperties properties = bind(Map.of());

        assertThat(properties.openai().model()).isEqualTo("gpt-6-luna");
        assertThat(properties.openai().reasoningEffort()).isEqualTo("low");
        assertThat(properties.openai().hasReasoningEffort()).isTrue();
    }

    @Test
    @DisplayName("키만 설정하면 모델과 추론 강도는 기본값이다")
    void apiKeyOnly_defaults() {
        TreasurerLlmProperties properties = bind(Map.of("openai.api-key", "test-key"));

        assertThat(properties.usesOpenAi()).isTrue();
        assertThat(properties.openai().model()).isEqualTo("gpt-6-luna");
        assertThat(properties.openai().reasoningEffort()).isEqualTo("low");
    }

    @Test
    @DisplayName("추론 강도를 빈 값으로 설정하면 보내지 않는 상태가 된다")
    void blankEffort_disablesReasoningEffort() {
        TreasurerLlmProperties properties = bind(Map.of(
                "openai.api-key", "test-key",
                "openai.reasoning-effort", ""
        ));

        assertThat(properties.openai().hasReasoningEffort()).isFalse();
    }

    @Test
    @DisplayName("설정한 모델과 추론 강도를 그대로 쓴다")
    void configuredValues_kept() {
        TreasurerLlmProperties properties = bind(Map.of(
                "openai.api-key", "test-key",
                "openai.model", "gpt-6.1-sol",
                "openai.reasoning-effort", "medium"
        ));

        assertThat(properties.openai().model()).isEqualTo("gpt-6.1-sol");
        assertThat(properties.openai().reasoningEffort()).isEqualTo("medium");
    }

    private TreasurerLlmProperties bind(Map<String, String> openAi) {
        Map<String, String> source = new HashMap<>();
        source.put("howmuch.treasurer.llm.provider", "openai");
        openAi.forEach((key, value) -> source.put("howmuch.treasurer.llm." + key, value));
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("howmuch.treasurer.llm", TreasurerLlmProperties.class)
                .get();
    }
}
