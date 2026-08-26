package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InquiryParser {
    private static final List<String> PERIOD_THIS_MONTH = List.of("이번달", "이번 달");
    private static final List<String> PERIOD_LAST_MONTH = List.of("지난달", "지난 달", "저번달", "저번 달");
    private static final List<String> SEND_KEYWORDS = List.of("보낼", "내야", "보내야", "낼정산", "송금");
    private static final List<String> RECEIVE_KEYWORDS = List.of("받을", "받을돈", "받을금액", "받아야");
    private static final List<String> EXPENSE_KEYWORDS = List.of("지출", "사용했", "사용내역", "소비", "결제");
    private static final List<String> STATUS_KEYWORDS = List.of("진행상태", "진행 상황", "진행상황", "정산상태", "어디까지");
    private static final Pattern TITLE_HINT = Pattern.compile("([가-힣A-Za-z0-9]{1,20}(?:모임|정산))");

    public ParsedInquiry parse(String question) {
        if (question == null || question.isBlank()) {
            return new ParsedInquiry(InquiryIntent.UNKNOWN, null, null);
        }
        String compact = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        InquiryPeriod period = periodOf(compact);
        InquiryIntent intent = intentOf(compact);
        String titleHint = titleHint(question);
        return new ParsedInquiry(intent, period, titleHint);
    }

    private InquiryPeriod periodOf(String compact) {
        if (containsAny(compact, PERIOD_LAST_MONTH)) {
            return InquiryPeriod.LAST_MONTH;
        }
        if (containsAny(compact, PERIOD_THIS_MONTH)) {
            return InquiryPeriod.THIS_MONTH;
        }
        return null;
    }

    private InquiryIntent intentOf(String compact) {
        if (containsAny(compact, SEND_KEYWORDS)) {
            return InquiryIntent.SEND_SETTLEMENTS;
        }
        if (containsAny(compact, RECEIVE_KEYWORDS)) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (containsAny(compact, EXPENSE_KEYWORDS)) {
            return InquiryIntent.EXPENSE_SUMMARY;
        }
        if (containsAny(compact, STATUS_KEYWORDS) || compact.contains("진행")) {
            return InquiryIntent.SETTLEMENT_STATUS;
        }
        return InquiryIntent.UNKNOWN;
    }

    private String titleHint(String question) {
        Matcher matcher = TITLE_HINT.matcher(question.replaceAll("\\s+", ""));
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!isQueryGrammar(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isQueryGrammar(String candidate) {
        String compact = candidate.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return containsAny(compact, PERIOD_THIS_MONTH)
                || containsAny(compact, PERIOD_LAST_MONTH)
                || containsAny(compact, SEND_KEYWORDS)
                || containsAny(compact, RECEIVE_KEYWORDS)
                || containsAny(compact, EXPENSE_KEYWORDS)
                || containsAny(compact, STATUS_KEYWORDS);
    }

    private boolean containsAny(String compact, List<String> phrases) {
        return phrases.stream().map(phrase -> phrase.replaceAll("\\s+", "")).anyMatch(compact::contains);
    }

    public record ParsedInquiry(InquiryIntent intent, InquiryPeriod period, String titleHint) {
    }
}
