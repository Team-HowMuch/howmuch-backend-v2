package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort.TreasurerAnswerDraft;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TreasurerLlmResponseParser {
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");
    private static final Pattern FENCE = Pattern.compile("(?i)```(?:json|markdown|text)?");

    private TreasurerLlmResponseParser() {
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

    static boolean usesOnlyKnownNumbers(String content, TreasurerAnswerDraft draft) {
        if (!StringUtils.hasText(content) || draft == null) {
            return false;
        }
        Set<Long> allowed = allowedNumbers(draft);
        Matcher matcher = NUMBER.matcher(content);
        while (matcher.find()) {
            if (isCalendarNumber(content, matcher.end())) {
                continue;
            }
            long value = Long.parseLong(matcher.group().replace(",", ""));
            if (!allowed.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static Set<Long> allowedNumbers(TreasurerAnswerDraft draft) {
        Set<Long> allowed = new HashSet<>();
        allowed.add(draft.totalAmount());
        allowed.add((long) draft.itemCount());
        if (draft.items() != null) {
            draft.items().forEach(item -> allowed.add(item.amount()));
        }
        if (StringUtils.hasText(draft.fallbackAnswer())) {
            Matcher matcher = NUMBER.matcher(draft.fallbackAnswer());
            while (matcher.find()) {
                allowed.add(Long.parseLong(matcher.group().replace(",", "")));
            }
        }
        return allowed;
    }

    private static boolean isCalendarNumber(String content, int end) {
        return end < content.length() && "월일년".indexOf(content.charAt(end)) >= 0;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isString()) {
            return null;
        }
        String value = node.stringValue();
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
