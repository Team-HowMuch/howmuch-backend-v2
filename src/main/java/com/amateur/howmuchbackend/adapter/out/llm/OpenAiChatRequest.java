package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 비서 OpenAI Chat Completions 요청 본문.
 *
 * <p>추론 모델은 {@code temperature}를 받지 않으므로 넣지 않는다. 추론 강도는 설정했을 때만 넣는다.
 */
final class OpenAiChatRequest {
    private OpenAiChatRequest() {
    }

    static Map<String, Object> body(TreasurerLlmProperties.OpenAi openai, String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", openai.model());
        if (openai.hasReasoningEffort()) {
            body.put("reasoning_effort", openai.reasoningEffort());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }
}
