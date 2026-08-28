package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TreasurerAnswerFactCheck {
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");

    public boolean accepts(String content, TreasurerAnswerDraft draft) {
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
}
