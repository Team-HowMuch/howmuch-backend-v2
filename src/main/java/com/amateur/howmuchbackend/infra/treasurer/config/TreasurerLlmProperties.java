package com.amateur.howmuchbackend.infra.treasurer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "howmuch.treasurer.llm")
public record TreasurerLlmProperties(
        String provider,
        Duration connectTimeout,
        Duration readTimeout,
        OpenAi openai,
        Gemini gemini
) {
    public TreasurerLlmProperties {
        provider = defaultIfBlank(provider, "none").toLowerCase();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
        openai = openai == null ? new OpenAi("", null, null, null) : openai;
        gemini = gemini == null
                ? new Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
                : gemini;
    }

    public boolean usesOpenAi() {
        return "openai".equals(provider) && openai.hasApiKey();
    }

    public boolean usesGemini() {
        return "gemini".equals(provider) && gemini.hasApiKey();
    }

    /**
     * OpenAI 설정.
     *
     * <p>{@code reasoningEffort}는 추론 모델에 보내는 {@code reasoning_effort} 값이다. 설정하지 않으면 {@code low}를 쓴다.
     * 빈 값으로 두면 요청에 넣지 않으므로 추론 모델이 아닌 모델도 설정만으로 쓸 수 있다.
     */
    public record OpenAi(
            String apiKey,
            String model,
            String baseUrl,
            String reasoningEffort
    ) {
        public OpenAi {
            apiKey = defaultIfBlank(apiKey, "");
            model = defaultIfBlank(model, "gpt-6-luna");
            baseUrl = defaultIfBlank(baseUrl, "https://api.openai.com/v1");
            reasoningEffort = reasoningEffort == null ? "low" : reasoningEffort.trim();
        }

        public boolean hasApiKey() {
            return StringUtils.hasText(apiKey);
        }

        public boolean hasReasoningEffort() {
            return StringUtils.hasText(reasoningEffort);
        }
    }

    public record Gemini(
            String apiKey,
            String model,
            String baseUrl
    ) {
        public Gemini {
            apiKey = defaultIfBlank(apiKey, "");
            model = defaultIfBlank(model, "gemini-flash-latest");
            baseUrl = defaultIfBlank(baseUrl, "https://generativelanguage.googleapis.com");
        }

        public boolean hasApiKey() {
            return StringUtils.hasText(apiKey);
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
