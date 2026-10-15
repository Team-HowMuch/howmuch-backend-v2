package com.amateur.howmuchbackend.adapter.out.llm;

import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.util.regex.Pattern;

final class TreasurerLlmResponseParser {
    private static final Pattern FENCE = Pattern.compile("(?i)```(?:json|markdown|text)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    static final int MAX_ERROR_BODY_LENGTH = 200;

    private TreasurerLlmResponseParser() {
    }

    /**
     * 실패 응답 본문을 로그에 남길 만큼만 줄인다. 거절 사유(지원하지 않는 파라미터 등)를 보려는 용도이며,
     * 요청에 담은 질문은 응답 오류 본문에 들어가지 않는다.
     */
    static String errorSummary(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String compact = WHITESPACE.matcher(body).replaceAll(" ").strip();
        return compact.length() <= MAX_ERROR_BODY_LENGTH ? compact : compact.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    static String openAiContent(JsonNode response) {
        if (response == null) {
            return null;
        }
        return text(response.path("choices").path(0).path("message").path("content"));
    }

    static String geminiContent(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.size() == 0) {
            return null;
        }
        StringBuilder combined = new StringBuilder();
        parts.forEach(part -> {
            String text = text(part.path("text"));
            if (StringUtils.hasText(text)) {
                if (!combined.isEmpty()) {
                    combined.append('\n');
                }
                combined.append(text);
            }
        });
        return combined.isEmpty() ? null : combined.toString();
    }

    static String sanitize(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String cleaned = FENCE.matcher(content).replaceAll("").replace("```", "");
        cleaned = cleaned.replace("**", "");
        cleaned = cleaned.replaceAll("(?m)^#{1,6}\\s*", "");
        cleaned = cleaned.trim();
        if (cleaned.length() > 500) {
            cleaned = cleaned.substring(0, 500).trim();
        }
        return StringUtils.hasText(cleaned) ? cleaned : null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isString()) {
            return null;
        }
        String value = node.stringValue();
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
