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
        openai = openai == null ? new OpenAi("", "gpt-4o-mini", "https://api.openai.com/v1") : openai;
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

    public record OpenAi(
            String apiKey,
            String model,
            String baseUrl
    ) {
        public OpenAi {
            apiKey = defaultIfBlank(apiKey, "");
            model = defaultIfBlank(model, "gpt-4o-mini");
            baseUrl = defaultIfBlank(baseUrl, "https://api.openai.com/v1");
        }

        public boolean hasApiKey() {
            return StringUtils.hasText(apiKey);
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
