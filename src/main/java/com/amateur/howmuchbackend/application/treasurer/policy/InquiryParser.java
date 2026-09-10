package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InquiryParser {
    private static final List<String> PERIOD_THIS_WEEK = List.of("이번주", "이번 주");
    private static final List<String> PERIOD_THIS_MONTH = List.of("이번달", "이번 달");
    private static final List<String> PERIOD_LAST_MONTH = List.of("지난달", "지난 달", "저번달", "저번 달");
    private static final List<String> SEND_KEYWORDS = List.of("보낼", "내야", "보내야", "낼정산", "송금");
    private static final List<String> RECEIVE_KEYWORDS = List.of("받을", "받을돈", "받을금액", "받아야");
    private static final List<String> UNPAID_TO_ME = List.of("송금안", "아직송금");
    private static final List<String> EXPENSE_KEYWORDS = List.of("지출", "사용했", "사용내역", "소비", "결제");
    private static final List<String> STATUS_KEYWORDS = List.of(
            "진행상태", "진행 상황", "진행상황", "진행중인", "진행 중인", "진행중", "진행 중",
            "정산상태", "정산 있어", "정산있어", "어디까지"
    );
    private static final List<String> DUE_KEYWORDS = List.of("마감해야 할 정산", "마감할 정산", "마감해야", "마감할", "마감");
    private static final List<String> GUIDE_KEYWORDS = List.of("ocr", "영수증");
    private static final List<String> FILLER_KEYWORDS = List.of("알려줘", "어떻게해", "어떻게", "누구야", "있어");
    private static final Pattern TITLE_SUFFIX = Pattern.compile("모임|정산");
    private static final List<String> TITLE_STOP_WORDS = List.of(
            "왜", "언제", "어디", "어떤", "무슨", "뭐", "누가", "누구", "얼마", "아직", "지금", "방금"
    );
    private static final int MIN_TITLE_PREFIX = 2;
    private static final int MAX_TITLE_PREFIX = 20;

    public ParsedInquiry parse(String question) {
        if (question == null || question.isBlank()) {
            return new ParsedInquiry(InquiryIntent.UNKNOWN, null, null);
        }
        String compact = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        InquiryPeriod period = periodOf(compact);
        InquiryIntent intent = intentOf(compact);
        String titleHint = usesTitleFilter(intent) ? titleHint(compact) : null;
        return new ParsedInquiry(intent, period, titleHint);
    }

    private InquiryPeriod periodOf(String compact) {
        if (containsAny(compact, PERIOD_THIS_WEEK)) {
            return InquiryPeriod.THIS_WEEK;
        }
        if (containsAny(compact, PERIOD_LAST_MONTH)) {
            return InquiryPeriod.LAST_MONTH;
        }
        if (containsAny(compact, PERIOD_THIS_MONTH)) {
            return InquiryPeriod.THIS_MONTH;
        }
        return null;
    }

    private InquiryIntent intentOf(String compact) {
        if (containsAny(compact, UNPAID_TO_ME)) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (containsAny(compact, DUE_KEYWORDS)
                && !containsAny(compact, SEND_KEYWORDS)
                && !containsAny(compact, RECEIVE_KEYWORDS)) {
            return InquiryIntent.DUE_SETTLEMENTS;
        }
        int sendAt = earliestIndex(compact, SEND_KEYWORDS);
        int receiveAt = earliestIndex(compact, RECEIVE_KEYWORDS);
        if (sendAt >= 0 && (receiveAt < 0 || sendAt <= receiveAt)) {
            return InquiryIntent.SEND_SETTLEMENTS;
        }
        if (receiveAt >= 0) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (containsAny(compact, EXPENSE_KEYWORDS)) {
            return InquiryIntent.EXPENSE_SUMMARY;
        }
        if (containsAny(compact, STATUS_KEYWORDS)) {
            return InquiryIntent.SETTLEMENT_STATUS;
        }
        if (containsAny(compact, GUIDE_KEYWORDS)) {
            return InquiryIntent.GUIDE;
        }
        return InquiryIntent.UNKNOWN;
    }

    private String titleHint(String compact) {
        boolean[] grammar = grammarMask(compact);
        Matcher matcher = TITLE_SUFFIX.matcher(compact);
        while (matcher.find()) {
            String candidate = titleAt(compact, grammar, matcher.start(), matcher.end());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 접미사(모임/정산) 앞을 문법 구간에 닿을 때까지만 거슬러 올라간다.
     * 문자열을 잘라내지 않으므로 후보는 항상 원문의 연속 부분 문자열이다.
     */
    private String titleAt(String compact, boolean[] grammar, int suffixStart, int suffixEnd) {
        int start = suffixStart;
        while (start > 0
                && !grammar[start - 1]
                && suffixStart - (start - 1) <= MAX_TITLE_PREFIX
                && isNameCharacter(compact.charAt(start - 1))) {
            start--;
        }
        String prefix = compact.substring(start, suffixStart);
        if (prefix.length() < MIN_TITLE_PREFIX || containsAny(prefix, TITLE_STOP_WORDS)) {
            return null;
        }
        return compact.substring(start, suffixEnd);
    }

    /** 문법 어휘가 차지한 문자 위치를 표시한다. 제목 후보는 이 구간을 넘지 못한다. */
    private boolean[] grammarMask(String compact) {
        boolean[] mask = new boolean[compact.length()];
        for (String phrase : grammarPhrases()) {
            String needle = compactPhrase(phrase);
            if (needle.isEmpty()) {
                continue;
            }
            int from = compact.indexOf(needle);
            while (from >= 0) {
                for (int i = from; i < from + needle.length(); i++) {
                    mask[i] = true;
                }
                from = compact.indexOf(needle, from + 1);
            }
        }
        return mask;
    }

    private List<String> grammarPhrases() {
        List<String> phrases = new ArrayList<>();
        phrases.addAll(PERIOD_THIS_WEEK);
        phrases.addAll(PERIOD_THIS_MONTH);
        phrases.addAll(PERIOD_LAST_MONTH);
        phrases.addAll(SEND_KEYWORDS);
        phrases.addAll(RECEIVE_KEYWORDS);
        phrases.addAll(UNPAID_TO_ME);
        phrases.addAll(EXPENSE_KEYWORDS);
        phrases.addAll(STATUS_KEYWORDS);
        phrases.addAll(DUE_KEYWORDS);
        phrases.addAll(GUIDE_KEYWORDS);
        phrases.addAll(FILLER_KEYWORDS);
        return phrases;
    }

    private boolean isNameCharacter(char value) {
        return (value >= '가' && value <= '힣')
                || (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private boolean containsAny(String compact, List<String> phrases) {
        return earliestIndex(compact, phrases) >= 0;
    }

    private int earliestIndex(String compact, List<String> phrases) {
        int earliest = -1;
        for (String phrase : phrases) {
            int index = compact.indexOf(compactPhrase(phrase));
            if (index >= 0 && (earliest < 0 || index < earliest)) {
                earliest = index;
            }
        }
        return earliest;
    }

    private boolean usesTitleFilter(InquiryIntent intent) {
        return intent == InquiryIntent.SEND_SETTLEMENTS
                || intent == InquiryIntent.RECEIVE_SETTLEMENTS
                || intent == InquiryIntent.SETTLEMENT_STATUS
                || intent == InquiryIntent.EXPENSE_SUMMARY
                || intent == InquiryIntent.DUE_SETTLEMENTS;
    }

    private String compactPhrase(String phrase) {
        return phrase.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record ParsedInquiry(InquiryIntent intent, InquiryPeriod period, String titleHint) {
    }
}
