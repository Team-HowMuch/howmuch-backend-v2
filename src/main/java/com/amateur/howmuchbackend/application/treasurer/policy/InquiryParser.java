package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;

import java.util.List;
import java.util.Locale;

public class InquiryParser {
    private static final List<String> PERIOD_THIS_MONTH = List.of("이번달", "이번 달");
    private static final List<String> PERIOD_LAST_MONTH = List.of("지난달", "지난 달", "저번달", "저번 달");
    private static final List<String> SEND_KEYWORDS = List.of("보낼", "내야", "보내야", "낼정산", "송금");
    private static final List<String> RECEIVE_KEYWORDS = List.of("받을", "받을돈", "받을금액", "받아야");
    private static final List<String> EXPENSE_KEYWORDS = List.of("지출", "사용했", "사용내역", "소비", "결제");
    private static final List<String> STATUS_KEYWORDS = List.of("진행상태", "진행 상황", "진행상황", "정산상태", "어디까지");

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
        java.util.regex.Matcher groupName = java.util.regex.Pattern.compile("([가-힣A-Za-z0-9]{1,20}모임)")
                .matcher(question.replaceAll("\\s+", ""));
        if (groupName.find()) {
            return groupName.group(1);
        }
        String remainder = question;
        remainder = stripPhrases(remainder, PERIOD_THIS_MONTH);
        remainder = stripPhrases(remainder, PERIOD_LAST_MONTH);
        remainder = stripPhrases(remainder, SEND_KEYWORDS);
        remainder = stripPhrases(remainder, RECEIVE_KEYWORDS);
        remainder = stripPhrases(remainder, EXPENSE_KEYWORDS);
        remainder = stripPhrases(remainder, STATUS_KEYWORDS);
        remainder = remainder.replaceAll("(?i)(정산|얼마|알려줘|조회|요약|해줘|은|는|이|가|에서|의|을|를|좀|현재|월|기준|내가|나는|저는|우리|야|줘)", "");
        remainder = remainder.replaceAll("[?!.]", "");
        remainder = remainder.replaceAll("\\s+", "").trim();
        if (remainder.length() < 3) {
            return null;
        }
        return remainder;
    }

    private boolean containsAny(String compact, List<String> phrases) {
        return phrases.stream().map(phrase -> phrase.replaceAll("\\s+", "")).anyMatch(compact::contains);
    }

    private String stripPhrases(String source, List<String> phrases) {
        String result = source;
        for (String phrase : phrases) {
            result = result.replace(phrase, "");
        }
        return result;
    }

    public record ParsedInquiry(InquiryIntent intent, InquiryPeriod period, String titleHint) {
    }
}
