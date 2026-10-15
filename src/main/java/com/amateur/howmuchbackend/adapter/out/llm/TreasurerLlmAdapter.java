package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class TreasurerLlmAdapter implements GenerateTreasurerAnswerPort {
    private static final String SYSTEM_PROMPT = """
            너는 HowMuch의 개인 정산 비서다.
            사용자 질문과 서버가 계산한 팩트만 보고 한국어로 2~4문장 답한다.
            금액·건수는 팩트에 있는 숫자만 쓰고 새로 만들지 않는다.
            인사나 마크다운, JSON은 쓰지 않는다.""";

    private final RestClient restClient;
    private final TreasurerLlmProperties properties;
    private final ObjectMapper objectMapper;

    public TreasurerLlmAdapter(
            @Qualifier("treasurerLlmRestClient") RestClient restClient,
            TreasurerLlmProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> generate(TreasurerAnswerDraft draft) {
        if (draft == null || (!properties.usesOpenAi() && !properties.usesGemini())) {
            return Optional.empty();
        }
        try {
            String generated = properties.usesOpenAi() ? completeOpenAi(draft) : completeGemini(draft);
            return Optional.ofNullable(TreasurerLlmResponseParser.sanitize(generated));
        } catch (Exception exception) {
            log.warn("Treasurer LLM call failed. {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    Map<String, Object> openAiBody(TreasurerAnswerDraft draft) {
        return OpenAiChatRequest.body(properties.openai(), SYSTEM_PROMPT, userPrompt(draft));
    }

    private String completeOpenAi(TreasurerAnswerDraft draft) throws JacksonException {
        Map<String, Object> body = openAiBody(draft);
        String raw = restClient.post()
                .uri(properties.openai().baseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.openai().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return TreasurerLlmResponseParser.openAiContent(objectMapper.readTree(raw));
    }

    private String completeGemini(TreasurerAnswerDraft draft) throws JacksonException {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt(draft))))
                ),
                "generationConfig", Map.of("temperature", 0.3)
        );
        String raw = restClient.post()
                .uri(properties.gemini().baseUrl() + "/v1beta/models/" + properties.gemini().model() + ":generateContent")
                .header("x-goog-api-key", properties.gemini().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return TreasurerLlmResponseParser.geminiContent(objectMapper.readTree(raw));
    }

    private String userPrompt(TreasurerAnswerDraft draft) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("질문: ").append(draft.question()).append('\n');
        prompt.append("의도: ").append(draft.intent().name()).append('\n');
        prompt.append("조회 범위: ").append(draft.scope()).append('\n');
        prompt.append("건수: ").append(draft.itemCount()).append('\n');
        prompt.append("총액(원): ").append(draft.totalAmount()).append('\n');
        prompt.append("항목:\n");
        if (draft.items() == null || draft.items().isEmpty()) {
            prompt.append("- 없음\n");
        } else {
            draft.items().forEach(item -> prompt.append("- ")
                    .append(item.title())
                    .append(" / ")
                    .append(item.direction())
                    .append(" / ")
                    .append(item.amount())
                    .append("원\n"));
        }
        prompt.append("서버 기본 안내: ").append(draft.fallbackAnswer());
        return prompt.toString();
    }
}
